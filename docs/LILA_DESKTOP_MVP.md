# Lila Desktop — visual-first MVP

Status: product and technical foundation; no executable Desktop app yet.

## Product intent

Lila Desktop is a Windows companion application that can show a Lila avatar on the desktop while also providing a normal chat window. The avatar is optional and can be shown or hidden independently of the chat window.

The first release is deliberately visual-only. It does not speak, listen, make autonomous decisions, or run arbitrary actions on the computer.

## MVP user experience

1. Launch the Desktop application and open its chat shell.
2. Press the avatar button to show or hide Lila as a desktop overlay.
3. Drag the avatar to reposition it.
4. Select a look from an avatar gallery.
5. Change emotion and pose using explicit UI controls.
6. Let the avatar play a small set of idle / walking-in-place animations.
7. Keep the avatar visible when the chat window is minimized, with a tray/menu control to bring the chat back.

## Initial state model

- visibility: hidden | visible
- look_id: stable catalog identifier
- emotion: neutral | happy | sad | curious | playful | affectionate | serious
- motion: idle | walk | pose
- pose_id: optional allowlisted pose identifier
- position: user-controlled desktop coordinates, persisted locally
- asset_version: version of the selected avatar asset bundle

All state transitions are explicit and validated. The first milestone can use local demo state; later the same state is driven by versioned events from Lila Core.

## Rendering architecture

Keep these responsibilities separate:

- **Desktop shell:** window lifecycle, tray/menu, overlay window, drag and position persistence.
- **Avatar Engine:** asset loading, layered composition, animation state machine and rendering.
- **Chat client:** conversation UI and image-generation presentation, calling Galaxy/Core APIs.
- **Core event adapter:** translates validated `avatar.emotion` and `avatar.action` events into allowlisted Avatar Engine state changes.

The model must never send raw JavaScript, CSS, HTML, filesystem paths or arbitrary commands to the renderer.

## Technology direction

Evaluate a lightweight Windows shell with a web-based renderer (Tauri 2 + TypeScript is a candidate) against a native Windows shell before committing. The decision must account for transparent always-on-top overlays, DPI scaling, multi-monitor coordinates, click-through behavior, accessibility, GPU use, packaging and the eventual Live2D/2D-rig renderer.

Do not lock the rendering technology before a small overlay proof-of-concept validates Windows behavior.

## Windows transparent-overlay feasibility

Status: documentation-based feasibility assessment only. No Windows proof-of-concept, executable Desktop code, native plugin or packaging configuration has been created.

### Preliminary decision

**Tauri 2 is feasible enough to be the first D0 proof-of-concept candidate, but it is not yet an approved production choice.** Its documented window API exposes transparency, always-on-top, undecorated windows, runtime visibility and cursor-event control. The unresolved acceptance risk is the exact Windows hit-testing behavior of transparent WebView pixels: the API's whole-window cursor pass-through is not equivalent to proving per-pixel click-through around an interactive avatar.

The D0 spike must answer that question on Windows before the framework is selected.

### Tauri 2 capabilities and constraints

- Create the avatar window as a transparent, undecorated, initially hidden top-level window. Evaluate `alwaysOnTop: true`, `skipTaskbar: true` and Windows `noRedirectionBitmap: true` in the window configuration.
- The WebView document itself must also have a transparent root/background. Tauri documents that the Windows window-background color API ignores its alpha channel; it must not be treated as the mechanism that makes the avatar surface transparent.
- Use the documented runtime window APIs for show/hide, positioning and always-on-top behavior. `setIgnoreCursorEvents(true)` ignores cursor events for the window as a whole. Treat it only as an explicit whole-overlay pass-through mode, not as proof of hit-testing transparent pixels independently.
- Tauri window-changing APIs require explicit capabilities/permissions; the future Desktop application should grant only the commands needed by the specific chat and overlay windows. Do not enable broad shell or filesystem capabilities for the renderer.
- Avoid Mica/Acrylic/blur effects in the first avatar overlay spike. They are optional shell decoration, not a substitute for per-pixel transparency, and add another rendering variable to the proof.

### Windows behavior that must be proven

Windows layered-window documentation describes per-pixel alpha hit testing: fully transparent pixels can pass mouse messages to windows beneath the layered window. The separate `WS_EX_TRANSPARENT` extended style passes input through the entire layered window. These are different behaviors.

Tauri's public API documents a whole-window `setIgnoreCursorEvents` switch, but does not by itself establish that a transparent WebView's zero-alpha pixels will be hit-tested exactly as required by the avatar UX. The spike must inspect actual behavior on the supported Windows build. If necessary, evaluate a narrowly scoped native HWND hit-test implementation; do not silently apply whole-window click-through while the avatar is expected to be draggable.

### Window ownership and chat minimization

The chat and avatar overlay must be independent top-level windows managed by the same application process.

Do **not** make the overlay an owned/parent window of the chat window. On Windows, Tauri's documented `parent` relationship maps to an owned window; an owned window is hidden when its owner is minimized. That would violate the MVP requirement that Lila remain visible while chat is minimized.

Coordinate both windows through application state and the tray/menu lifecycle instead:

- minimizing or hiding chat does not hide the overlay;
- the tray/menu can show chat and independently show/hide the overlay;
- exiting the application closes both windows;
- overlay visibility is not coupled to chat focus.

Always-on-top means above ordinary non-topmost windows; it is not a promise to appear above the secure desktop, UAC consent UI or the lock screen.

### DPI and multi-monitor placement

Windows recommends per-monitor DPI awareness, preferably Per-Monitor V2 where supported, so a window can respond when moved between displays with different scale factors. The spike must explicitly test mixed-DPI displays rather than assuming that saved desktop coordinates remain valid.

Persist placement as a monitor identity plus a normalized anchor and logical-pixel (DIP) offset, not as an unqualified absolute coordinate alone. On restore, resolve the monitor if it still exists; otherwise clamp the avatar into the primary monitor's work area. Convert logical and physical coordinates deliberately at the native/window boundary.

### D0 proof-of-concept acceptance criteria

The spike is successful only when all of the following are demonstrated on a real Windows 11 x64 machine:

1. Chat and overlay are separate top-level windows. The overlay starts hidden and can be shown/hidden independently.
2. Minimizing the chat leaves the visible overlay on screen; the tray/menu can restore chat without changing overlay visibility.
3. The avatar window has a genuinely transparent page/window surface: desktop content is visible outside the avatar silhouette, with no persistent white/black rectangle or first-show flash.
4. The avatar can be dragged without moving, resizing or focusing the chat window.
5. A click on the visible avatar is delivered to the overlay. Transparent pixels outside the avatar pass through to the underlying application while normal avatar interaction remains enabled. If this cannot be achieved reliably with the Tauri window alone, record the native hit-test requirement and compare the smallest native HWND adapter; do not claim the criterion passed.
6. The overlay stays above ordinary application windows when configured always-on-top, and can be hidden immediately through the chat control and tray/menu.
7. Moving the avatar between monitors at different scale factors does not cause a coordinate jump or make it unreachable. Position is restored after restart and safely clamped if the saved monitor is disconnected.
8. The overlay does not steal keyboard focus merely by being shown; keyboard-accessible controls remain available through the chat/tray UI.
9. No microphone, speaker, administrator privilege, provider key, autonomous action permission or broad filesystem capability is required.
10. Record idle CPU/GPU use, animation frame pacing, first-show latency and behavior after display sleep/resume. These measurements inform the renderer choice; they are not yet fixed numeric budgets.

If the Tauri spike cannot satisfy transparent hit testing, stable mixed-DPI placement and independent window lifecycle without brittle or broad native workarounds, stop and run a small native Windows shell comparison before choosing the stack.

### Feasibility references

- [Tauri v2 Window API](https://v2.tauri.app/reference/javascript/api/namespacewindow/) — transparency, always-on-top, runtime visibility, cursor-event control and window permissions.
- [Tauri v2 configuration reference](https://v2.tauri.app/reference/config/) — transparent, no-redirection-bitmap, skip-taskbar, parent and window configuration.
- [Microsoft: Window Features — Layered Windows](https://learn.microsoft.com/windows/win32/winmsg/window-features) — per-pixel alpha hit testing and layered-window behavior.
- [Microsoft: Extended Window Styles](https://learn.microsoft.com/windows/win32/winmsg/extended-window-styles) — `WS_EX_LAYERED`, `WS_EX_TRANSPARENT` and `WS_EX_TOPMOST`.
- [Microsoft: High-DPI Desktop Application Development](https://learn.microsoft.com/en-us/windows/win32/hidpi/high-dpi-desktop-application-development-on-windows) — per-monitor DPI behavior and scaling.
- [Microsoft: DPI Awareness Context](https://learn.microsoft.com/windows/win32/hidpi/dpi-awareness-context) — Per-Monitor V2 context.

## Asset format

Start with an explicit manifest rather than hard-coded file names. Each look should declare:
- stable look ID and display name;
- asset bundle version;
- layered image assets and their z-order;
- supported emotions and poses;
- animation clips and frame/rate metadata;
- attribution and license metadata.

Keep source art separate from generated runtime caches. Do not commit private user reference images or generated personal media without explicit approval.

## Milestones

### D0 — Desktop shell proof
A Windows window opens; an avatar overlay can be shown, hidden and dragged. No Core connection.

### D1 — Avatar Studio integration
Gallery, look switching, emotion controls and asset manifest. Local demo state only.

### D2 — Motion
Idle and walking animation, pose switching, stable positioning across restarts and monitors.

### D3 — Chat shell
Desktop chat window with image-generation results presented in the conversation. Requests route through Galaxy services; credentials remain server-side.

### D4 — Core events
Authenticated connection to Lila Core; receive versioned avatar events and update visual state.

### D5 — Voice synchronization
Only after D4 is stable: speech-driven mouth movement and voice-linked expressions.

## Acceptance criteria for the first visual MVP

- Chat window and avatar overlay can be opened independently.
- Avatar can be shown, hidden and dragged without moving the chat window.
- At least three catalog looks can be selected without restarting the app.
- At least five emotions can be selected and visibly reflected.
- The renderer rejects unknown emotion/action identifiers.
- User position and selected look survive an application restart.
- No microphone, speaker, model-provider key or autonomous desktop-action permission is required.

## Repository boundary

This specification is staged in the Galaxy repository because a separate Lila Desktop repository has not yet been provisioned through the available GitHub integration. Desktop implementation must move to its own repository before product code begins; it must not be added to the Android Gradle application module.
