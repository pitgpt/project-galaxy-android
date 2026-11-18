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
