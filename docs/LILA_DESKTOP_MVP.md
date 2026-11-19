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
- [Microsoft: DPI Awareness Context](https://learn.microsoft.com/en-us/windows/win32/hidpi/dpi-awareness-context) — Per-Monitor V2 context.

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


## Avatar Engine contract — draft v1

Status: platform-neutral specification only. This contract is not executable code and does not select a rendering library.

### Responsibility boundary

The Avatar Engine is a deterministic visual-state reducer and renderer. It owns:
- validated avatar visual state;
- look/layer composition;
- pose and motion selection;
- emotion presentation and transitions;
- asset resolution by manifest ID;
- renderer lifecycle and state notifications.

It does **not** own:
- chat/overlay window creation, visibility, focus, z-order or desktop coordinates;
- authentication, network requests, model-provider credentials or conversation persistence;
- arbitrary filesystem access, shell execution, keyboard hooks or autonomous computer actions;
- generation or permanent storage of user media.

The Desktop Shell owns windows and local user preferences. The Core Event Adapter is the only boundary that translates validated Lila Core events into Avatar Engine operations.

### Canonical state

The engine state is deliberately separate from shell state:

| Field | Type / allowed values | Owner |
|---|---|---|
| `bundle_id` | stable manifest identifier | Asset package |
| `bundle_version` | semantic version | Asset package |
| `look_id` | ID present in the loaded manifest | Avatar Engine / explicit user selection |
| `emotion` | `neutral`, `happy`, `sad`, `curious`, `playful`, `affectionate`, `serious` | Core event or user control |
| `intensity` | finite number in [0, 1] | Emotion state |
| `motion` | `idle`, `walk`, `pose` | Engine state |
| `pose_id` | allowlisted manifest pose ID or null | User control / allowlisted Core action |
| `revision` | monotonically increasing process-local integer | Engine |

Overlay visibility, monitor identity, position and window focus are **not** Avatar Engine fields. They remain Desktop Shell state and cannot be changed by a model event.

### Lila Core event compatibility

The adapter consumes the versioned Pydantic event shapes currently defined in `backend/galaxy_backend/lila_core/contracts.py`:

- `avatar.emotion`, version 1: `event_id`, `type`, `version`, and payload `emotion` plus `intensity` in [0, 1].
- `avatar.action`, version 1: `event_id`, `type`, `version`, and payload `action` plus scalar `parameters`.

The Core currently constrains emotion values but leaves the action name as a bounded string. Therefore Desktop must apply its own strict allowlist; it must never interpret an action name as a method name, path, command or renderer script.

Initial adapter mapping:

| Core event | Accepted payload | Engine effect |
|---|---|---|
| `avatar.emotion` | one of the seven Core emotions; finite intensity 0–1 | Update emotion and intensity |
| `avatar.action` with `pose.set` | `pose_id` string that exists and is supported by the selected look | Select that pose |
| `avatar.action` with `motion.set` | `motion` equal to `idle`, `walk` or `pose` | Select an available motion |
| Any other action | Any | Reject as unsupported; leave state unchanged |

Additional rules:
- Look selection is initiated only by an explicit local UI action in the MVP; a model event cannot silently change clothing/look.
- Overlay show/hide, window movement, focus, tray operations and all OS interactions are never model-addressable.
- A `pose` motion requires a valid current `pose_id`; a `walk` motion requires a matching animation clip in the selected look. Missing capabilities produce a typed rejection, not an invented fallback.
- Repeated `event_id` values are idempotent within the bounded in-memory deduplication window. A duplicate must not increment the state revision or replay a transition.
- Unknown event versions, unknown emotions, invalid scalar types, NaN/infinite intensity, unknown pose IDs and unsupported action names are rejected without partially mutating state.

### Proposed engine API shape

The eventual implementation should expose an equivalent typed interface, independent of Tauri, Win32 or a particular 2D renderer:

```text
loadBundle(manifest, assetResolver) -> LoadResult
getState() -> AvatarEngineState
selectLook(lookId, origin = explicit_user_action) -> StateResult
setEmotion(emotion, intensity, origin) -> StateResult
setPose(poseId, origin) -> StateResult
setMotion(motion, origin) -> StateResult
applyCoreEvent(versionedEvent) -> EventResult
subscribeState(listener) -> unsubscribe
dispose() -> void
```

Results distinguish `applied`, `duplicate`, `unsupported`, `invalid` and `asset_unavailable`. Failed operations leave the previous state intact. State notifications are emitted only after a complete validated transition.

`assetResolver` resolves an opaque `asset_id` declared in the validated manifest. The engine never accepts a raw path or arbitrary URL from Core, model output or UI input. Renderer-specific objects and handles do not cross the Core contract.

## Asset Manifest — draft v1

The manifest is a versioned data document, not executable content. It describes a self-contained look bundle and all references required to render it.

### Top-level shape

| Field | Requirement |
|---|---|
| `schema_version` | Required integer; initial value 1 |
| `bundle_id` | Required stable lowercase identifier |
| `bundle_version` | Required semantic version |
| `display_name` | Required user-facing name |
| `default_look_id` | Required reference to one declared look |
| `assets` | Required list of unique asset records |
| `looks` | Required list of unique look records |
| `poses` | Required list of unique pose records |

### Example manifest

This is illustrative metadata only; the digest and dimensions are placeholders and do not identify or include a real image.

```json
{
  "schema_version": 1,
  "bundle_id": "lila.default",
  "bundle_version": "1.0.0",
  "display_name": "Lila — Default",
  "default_look_id": "default",
  "assets": [
    {
      "asset_id": "body.base",
      "path": "assets/body/base.webp",
      "media_type": "image/webp",
      "size_bytes": 123456,
      "width": 1024,
      "height": 2048,
      "sha256": "<64-lowercase-hex-digest>",
      "license": {
        "spdx_id": "CC-BY-4.0",
        "attribution": "Required creator attribution",
        "source_url": "https://example.invalid/source"
      }
    }
  ],
  "looks": [
    {
      "look_id": "default",
      "display_name": "Default look",
      "layers": [
        {
          "layer_id": "body",
          "asset_id": "body.base",
          "z_index": 0
        }
      ],
      "supported_emotions": [
        "neutral", "happy", "sad", "curious",
        "playful", "affectionate", "serious"
      ],
      "supported_poses": ["stand.neutral"],
      "animations": [
        {
          "animation_id": "idle.default",
          "motion": "idle",
          "fps": 12,
          "loop": true,
          "frames": ["body.base"]
        }
      ]
    }
  ],
  "poses": [
    {
      "pose_id": "stand.neutral",
      "display_name": "Neutral standing pose"
    }
  ]
}
```

### Asset and license metadata

Each asset record declares:
- stable unique `asset_id`;
- package-relative `path`;
- allowlisted `media_type` (initial raster formats: PNG and WebP);
- declared byte size and pixel dimensions;
- SHA-256 digest of the exact packaged bytes;
- license identifier/name, attribution and optional source reference.

The manifest source URL is attribution metadata only. The application must not fetch it automatically. License metadata must be displayed or retained wherever required by the asset's license; unknown or missing redistribution rights block bundling until resolved.

### Look, layer, pose and animation references

- A look has a stable ID, display name, ordered layers and explicit supported emotion/pose IDs.
- Every layer references an `asset_id`; it never embeds a filesystem path.
- Layer `z_index` values are unique within a look and define deterministic composition order.
- Every pose ID is unique and must be referenced by a look before it can be selected.
- Animation clips use a declared motion (`idle`, `walk` or `pose`), bounded positive frame rate, loop flag and an ordered list of asset IDs. Pose-specific clips also identify their `pose_id`.
- Every referenced asset, pose and animation must resolve within the same validated bundle version. No implicit filename discovery is allowed.

### Manifest validation and loading rules

Reject a bundle before rendering if any of these conditions hold:
1. Unsupported schema version, malformed bundle ID/version or duplicate IDs.
2. Missing default look or unresolved asset/look/pose/animation reference.
3. Absolute paths, drive-letter paths, UNC paths, backslashes used as separators, NULs, encoded traversal or any normalized path escaping the package root.
4. Remote URLs, data URLs, executable/script assets or unapproved media types.
5. Byte-size, dimensions or decoded-pixel counts exceeding application-configured limits.
6. SHA-256 or declared-size mismatch after reading the packaged bytes.
7. Duplicate layer order, invalid FPS/frame list, unsupported emotion or pose not declared for that look.
8. Missing/unknown license metadata where redistribution rights have not been established.

The loader must validate the manifest and byte limits before decoding image data. It must not follow symlinks outside the package root. A digest verifies integrity against the manifest; it does not establish publisher authenticity. Bundle authenticity/signing remains a packaging decision for a later milestone.

### Storage and privacy boundary

- Shipped, redistributable artwork may live in a versioned application asset bundle after license review.
- User-specific generated images and private reference images are not source assets and must not be copied into this manifest or committed to the repository.
- Any persistent user-generated media must use the platform's protected media-storage design before Desktop caching is implemented. Until that storage exists, use in-memory previews or explicitly non-persistent test fixtures only.
- Generated runtime thumbnails/cache are disposable derived data, not source-of-truth assets. Their storage location, encryption and deletion policy must be defined before D1 implementation.

## D1 conformance test plan

These are future automated test cases for the separate Desktop repository; no test code is added to Galaxy:

1. A valid manifest loads and resolves the declared default look.
2. Duplicate bundle, asset, look or pose IDs are rejected.
3. Missing asset/pose/animation references are rejected before rendering.
4. Absolute, drive-letter, UNC and traversal paths are rejected.
5. Declared byte size, dimensions and SHA-256 mismatches are rejected before image decode.
6. An unsupported media type or remote asset URL is rejected without network access.
7. Unsupported emotion and unknown Core event version leave state unchanged.
8. `pose.set` rejects a pose not supported by the current look.
9. `motion.set` rejects a motion without a matching clip.
10. Replaying the same event ID is idempotent and does not advance state revision.
11. Unknown action identifiers cannot invoke shell/window operations or arbitrary renderer methods.
12. A model event cannot change look selection, overlay visibility, desktop position or window focus.
13. A failed asset load leaves the previous visible state intact and reports a typed result.
14. License/attribution metadata remains discoverable from every selected look's assets.

D0 Windows overlay acceptance remains separate and must still be demonstrated on Windows 11 x64. These manifest/engine conformance cases do not substitute for native transparency, hit-testing, DPI or lifecycle tests.

## Repository boundary and current blocker

This document remains the only Desktop artifact in the Galaxy repository. The separate Lila Desktop repository is still not available through the current integration. No executable Desktop code, JSON Schema file, renderer package, native plugin, Windows project or generated/user media is added here. Once the separate repository is available, move this specification there as its initial architecture document before creating product code.
