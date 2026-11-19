# Lila Desktop — visual-first MVP

Status: product and technical foundation; no executable Desktop app yet.

## Product intent

Lila Desktop is a Windows companion application that can show a Lila avatar on the desktop while also providing a normal chat window. The avatar is optional and can be shown or hidden independently of the chat window.

The first release is deliberately visual-only. It does not speak, listen, make autonomous decisions, or run arbitrary actions on the computer.

## MVP user experience

1. Launch the Desktop application and open its chat shell.
2. Press the avatar button to show or hide Lila as a desktop overlay.
3. Drag the avatar to reposition it.
4. Load one fixed, approved Live2D appearance; the MVP has no look gallery or runtime appearance switching.
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

All state transitions are explicit and validated. For the fixed-appearance MVP, `look_id` identifies the one configured model/look and cannot be changed at runtime. The first milestone can use local demo state; later emotion, pose and motion are driven by versioned events from Lila Core.

## Rendering architecture

Keep these responsibilities separate:

- **Desktop shell:** window lifecycle, tray/menu, overlay window, drag and position persistence.
- **Avatar Engine:** asset loading, layered composition, animation state machine and rendering.
- **Chat client:** conversation UI and image-generation presentation, calling Galaxy/Core APIs.
- **Core event adapter:** translates validated `avatar.emotion` and `avatar.action` events into allowlisted Avatar Engine state changes.

The model must never send raw JavaScript, CSS, HTML, filesystem paths or arbitrary commands to the renderer.

## Technology direction

Evaluate a lightweight Windows shell with a web-based renderer (Tauri 2 + TypeScript is a candidate) against a native Windows shell before committing. Live2D is the first concrete avatar-rendering target; validate runtime licensing, redistribution and Windows integration. The shell decision must account for transparent always-on-top overlays, DPI scaling, multi-monitor coordinates, click-through behavior, accessibility, GPU use and packaging.

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

Start with an explicit manifest rather than hard-coded file names. The MVP ships one fixed approved Live2D model/look. Keep the manifest versioned and extensible for future compatible appearance bundles, but do not implement appearance generation or switching in the MVP. Each declared look should describe:
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

### D1 — Fixed-appearance Live2D integration
Load one approved Live2D model with a fixed appearance. Validate renderer lifecycle, scaling, anchoring, asset loading and performance. No look gallery or appearance switching.

### D2 — Motion and expressions
Idle animation, supported movement/walking, pose switching and emotion controls; stable positioning across restarts and monitors.

### D3 — Chat shell
Desktop chat window with conversation UI. Requests route through Galaxy/Lila Core services; credentials remain server-side.

### D4 — Shared Core events
Authenticated connection to Lila Core; receive versioned, allowlisted emotion, pose and motion events. The model/look remains fixed.

### D5 — Voice synchronization
Only after D4 is stable: speech-driven mouth movement and voice-linked expressions.

### D6 — Deferred appearance operations (after MVP)
Only after Galaxy, Lila Core, Desktop chat and the fixed-appearance Live2D avatar are integrated and validated, consider an Avatar Operations Manager and prompt-driven appearance generation. This is not part of the MVP.

## Acceptance criteria for the first visual MVP

- Chat window and avatar overlay can be opened independently.
- Avatar can be shown, hidden and dragged without moving the chat window.
- Exactly one approved Live2D model/look is used by the MVP; its fixed appearance survives application restart.
- At least five emotions can be selected and visibly reflected.
- The renderer rejects unknown emotion/action identifiers.
- User position and the configured fixed model/look survive an application restart.
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


## Desktop Shell state and lifecycle contract — draft v1

Status: specification only. This section defines the Desktop Shell's local state and Windows session lifecycle; it does not add executable Desktop code or select a persistence library.

### Responsibility and state separation

The Desktop Shell owns operating-system window state and user-local presentation preferences. The Avatar Engine owns only the visual character state defined above. Lila Core events may update allowlisted avatar emotion/pose/motion, but may never show, hide, move, focus, resize, or change the z-order of a Windows window.

Keep three state classes distinct:

| State class | Examples | Persistence |
|---|---|---|
| User presentation preferences | selected bundle/version/look; chat window geometry; overlay placement | Persist locally after validation |
| Runtime window/session state | chat visible/minimized; overlay visible; keyboard focus; visibility before session lock | Memory only; never restored as visible after a cold process start |
| Avatar Engine state | current emotion, intensity, pose, motion, process-local event deduplication | Engine-owned; only explicitly selected look is mirrored into shell preferences |

### Versioned local preference snapshot

The proposed shell snapshot is a versioned, non-executable data document. Its logical fields are:

| Field | Requirement |
|---|---|
| `schema_version` | Required supported integer; migrations are explicit and ordered |
| `chat_geometry` | Optional monitor identity, logical size in DIP, normalized work-area anchor and DIP offset |
| `overlay_placement` | Optional monitor identity, normalized work-area anchor, window anchor and DIP offset |
| `selected_bundle_id` | Opaque ID from a validated installed bundle |
| `selected_bundle_version` | Exact validated bundle version |
| `selected_look_id` | Opaque look ID present in that bundle |
| `updated_at` | Diagnostic timestamp; not an ordering or trust boundary |

For placement, the normalized work-area anchor uses finite x/y values in [0, 1]. The window anchor is one of `top_left`, `top_right`, `bottom_left`, `bottom_right`, or `center`. The DIP offset is a bounded signed pair applied after resolving the monitor's current work area. This avoids persisting an unqualified physical desktop coordinate that becomes invalid after DPI or display-topology changes.

The preferred monitor identifier is an opaque Windows display-device identity, not a user-entered path. It is a lookup hint, not a guarantee that the same physical monitor will always be connected.

### Persistence and privacy rules

- Store only validated presentation preferences in an app-specific, user-scoped local application-data location. The final product path and storage implementation remain a Desktop-repository decision.
- Write a complete new snapshot to a temporary file in the same directory, flush it, and atomically replace the prior snapshot. Never stream partial JSON over the last known-good state.
- Apply current-user-only filesystem access controls where supported. Do not use roaming/cloud synchronization by default.
- Do not store access tokens, provider keys, JWTs, prompts, conversation history, memory snippets, generated image/video bytes, private reference images, or model responses in this shell snapshot.
- Secrets, if ever needed by a future Desktop client, require a separate reviewed Windows credential-storage design; they must not be added to this preference file.
- Validate schema version, field types, numeric bounds, IDs and referenced installed bundle/look before applying a snapshot. Unknown major versions or malformed state fall back to safe defaults without executing content or following paths from the file.
- A corrupt or unsupported snapshot is not automatically deleted. Diagnostics must not log its raw contents. A later successful explicit preference save may atomically replace it with a newly validated snapshot.
- No user media or image asset is copied to disk as part of this specification. Persistent user media remains blocked on the protected-media design.

### Cold-start defaults

- A fresh process starts with the overlay hidden, regardless of its visibility before the previous process exited or crashed.
- The chat appears only through the normal user launch/show action; tray restoration may show it without changing overlay visibility.
- Restore the last validated selected look if its bundle version is still installed. If it is unavailable, select the bundle's declared default look and report a recoverable local status; do not fetch or install assets automatically.
- Restore placement after resolving the current monitor topology and DPI. If no valid placement exists, use a deterministic safe location inside the primary monitor's work area.
- Visibility, focus and lock state are never read from the persisted preference snapshot.

### Windows lifecycle policy

| Trigger | Required behavior |
|---|---|
| Chat minimized or hidden to tray | Overlay visibility is unchanged; the two top-level windows remain independent |
| User hides overlay | Hide only the overlay; chat and tray remain usable |
| User exits application | Close both windows and stop renderer timers; persist only validated preference state |
| Windows session locks | Immediately hide the overlay, stop animation/timers and relinquish focus; never attempt to appear above the secure desktop |
| Session unlocks in the same process | Restore the overlay only if it was visible immediately before that lock and the user has not hidden it meanwhile |
| Process starts while session is locked | Keep overlay hidden; do not schedule an automatic show over the lock screen |
| Display sleeps / system suspends | Pause animation and nonessential polling; do not spin timers while suspended |
| Resume from sleep | Re-enumerate displays, refresh per-monitor DPI/work areas and resolve placement before optionally restoring same-session visibility |
| Saved monitor is disconnected | Use the primary monitor's work area, clamp the complete overlay rectangle inside it, and retain the preferred monitor identity for a later explicit restore/restart |
| Display topology or scale changes | Recompute placement using current work area and Per-Monitor V2 DPI context; do not reuse stale physical-pixel coordinates |
| Preference file is malformed or has an unknown major schema | Use safe defaults, keep overlay hidden, preserve the original file and emit a sanitized diagnostic |
| Avatar asset/bundle is missing after update | Keep the previous valid in-memory render if available; otherwise use a validated default look and do not load an arbitrary path |

Always-on-top applies only above ordinary application windows. It never means above the Windows lock screen, secure desktop, UAC consent UI, or other protected system surfaces.

### Placement restoration algorithm

1. Resolve the saved monitor identity against the current display list.
2. If it is unavailable, choose the primary monitor for this restore operation; do not silently rewrite the preferred identity merely because a monitor is temporarily disconnected.
3. Read that monitor's current work area and DPI in the active Per-Monitor V2 context.
4. Convert the normalized work-area anchor and bounded DIP offset at the native/window boundary.
5. Apply the saved window anchor, then clamp the complete overlay rectangle inside the selected work area.
6. If the saved size cannot fit, apply the renderer's documented safe fit limit while preserving aspect ratio; never leave the avatar unreachable outside the work area.
7. Persist a new placement only after an explicit user move/resize or a successful validated preference save.

### D0 shell lifecycle acceptance criteria

The Windows 11 x64 proof must demonstrate all of the following; none is claimed as passed by this documentation change:

1. A cold process start always leaves the overlay hidden, even if it was visible before the previous process exited.
2. Showing/hiding the overlay does not show, hide, minimize or focus the chat window.
3. Minimizing the chat leaves an already visible overlay unchanged.
4. Locking Windows hides the overlay and stops animation; unlocking restores it only within the same process session if it was visible before lock.
5. Sleep/resume refreshes display topology and DPI before any same-session overlay restoration.
6. Disconnecting the saved monitor moves the overlay fully into the primary monitor's work area without a coordinate jump or unreachable window.
7. Moving between mixed-DPI monitors preserves the intended anchor and logical size.
8. A valid preference snapshot restores the selected installed look and placement after restart, while overlay visibility remains hidden.
9. Malformed, stale or unknown-major-version preference data cannot crash startup, load arbitrary assets, or make the overlay visible.
10. Preference writes are atomic: simulated interruption leaves either the prior complete snapshot or the new complete snapshot, never a partial document.
11. Shell/window actions are accepted only from local UI/tray/session lifecycle code; Core/model events cannot invoke them.
12. The preference snapshot contains no token, prompt, conversation, memory, generated-media or private-reference content.

### Future shell-state conformance tests

These are planned tests for the separate Lila Desktop repository, not tests executed in Galaxy:

1. Cold-start overlay visibility defaults to hidden.
2. Chat minimize does not change overlay visibility.
3. Overlay hide does not change chat visibility or focus.
4. Lock/unlock follows the same-session visibility rule.
5. A process started while Windows is locked keeps the overlay hidden.
6. Sleep/resume refreshes monitor and DPI metadata before restoring visibility.
7. Missing-monitor fallback clamps the full overlay rectangle to the primary work area.
8. Mixed-DPI restoration preserves the normalized anchor and DIP offset.
9. A valid snapshot restores only an installed bundle/version/look.
10. Corrupt and unknown-major snapshots fall back safely without destructive automatic deletion.
11. Interrupted preference writes preserve a complete old or new snapshot.
12. Serialized preferences contain no credentials, conversation content, memory or user media.

These shell-state tests complement, but do not replace, the 14 Avatar Engine/Asset Manifest D1 conformance cases and the ten D0 native transparency/hit-testing/window-lifecycle checks above.

### Open implementation decisions

The separate Desktop repository must still choose and validate the concrete single-instance mechanism, tray integration, Windows session notification API, preference serializer, atomic-replace implementation and Per-Monitor V2 integration. These choices must be proven in the Windows 11 x64 PoC; this document does not assume a Tauri plugin or native adapter already exists.
