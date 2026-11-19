# Lila Desktop — phased avatar scope decision

Status: architecture decision for the visual-first MVP. This document narrows the implementation scope of `LILA_DESKTOP_MVP.md` and takes precedence wherever the older draft describes look galleries or appearance switching as first-release requirements.

## Decision

The first Lila Desktop avatar is a **Live2D 2D character with one fixed, approved appearance**.

The initial goal is to obtain a working desktop avatar that can be rendered, animated, moved around the Windows desktop, express supported emotions, change supported poses, and remain synchronized with the same Lila identity and conversation used by Galaxy Android.

Dynamic appearance changes, prompt-driven clothing generation, generated-asset ingestion, and an Avatar Operations Manager are explicitly deferred until the core ecosystem and the fixed-appearance Live2D avatar are working and validated.

This is a sequencing decision, not a rejection of future appearance customization.

## Delivery phases

### A0 — Windows Desktop shell proof

- Independent chat and avatar top-level windows.
- Transparent, draggable overlay with safe placement and lifecycle behavior.
- No Core dependency and no dynamic appearance functionality.
- Validate the Windows 11 behavior described by the D0 acceptance criteria.

### A1 — Fixed-appearance Live2D avatar

- Integrate one approved Live2D model and its licensed/runtime-compatible assets.
- Load one fixed model/look at startup; no look gallery and no outfit switching.
- Establish renderer lifecycle, model loading, scaling, anchoring and rendering performance.
- Keep model assets versioned and addressed through the asset manifest.
- Do not generate, download, modify or persist new appearance assets at runtime.

### A2 — Motion and interaction

- Idle animation and supported movement/walking behavior.
- Supported pose and expression changes.
- Dragging and repositioning remain Desktop Shell responsibilities.
- The avatar continues rendering and animating independently of chat-window minimization.
- Test animation pacing, resource use, display sleep/resume and multi-monitor behavior.

### A3 — Chat and shared Lila Core

- Desktop chat uses the same authenticated Lila Core identity and conversation services as Galaxy, rather than creating a second persona or independent memory.
- Consume only validated, versioned Core events.
- Core may request allowlisted emotion, pose and motion changes.
- Core/model output cannot change the selected model/look, load arbitrary assets, or invoke shell/window operations.

### A4 — Voice

- Add voice interaction and speech-synchronized expressions only after chat/Core integration and avatar motion are stable.
- Voice must use the same conversation identity and context as text chat.

### A5 — Ecosystem validation

- Verify one continuing conversation and owner-scoped memory across Galaxy Android and Lila Desktop.
- Verify reconnect, restart, authentication expiry and offline/local-model behavior.
- Verify the avatar remains visually stable while Core/model services reconnect or fail.

### A6 — Deferred appearance system (not part of the MVP)

Only after A0–A5 are implemented and validated may work begin on:
- Avatar Operations Manager / Appearance Manager;
- prompt interpretation for appearance requests;
- image or outfit generation through ComfyUI or another approved provider;
- preview, approval, validation and application of generated assets;
- compatible Live2D layer/parameter preparation and asset packaging;
- versioned appearance catalog, rollback and protected media persistence.

No implementation of this phase is authorized by this decision. It is a future extension point only.

## Architecture constraints now

1. **Keep the Avatar Engine renderer-independent.** Its typed API and state reducer must not depend on Tauri, Win32 or a particular Live2D runtime.
2. **Use Live2D as the first concrete character renderer.** The Desktop implementation must validate the applicable Live2D runtime, licensing, distribution and Windows integration before committing to it.
3. **Keep the appearance identity stable in the MVP.** A single bundle/version/look is selected by local application configuration. There is no runtime look-selection UI in the first working avatar milestone.
4. **Preserve a future asset boundary.** The versioned manifest and opaque asset IDs remain the only route by which the renderer resolves model assets. This allows later compatible appearance bundles without adding arbitrary file paths or URLs to Core events.
5. **Do not build a speculative clothing system now.** No generation jobs, prompt schema, garment layers, generated-media database, appearance workflow, or Avatar Operations Manager service in A0–A5.
6. **Keep Core authority narrow.** The current Core-to-avatar contract remains limited to allowlisted emotion, pose and motion events. Appearance changes are not model-addressable.
7. **Do not confuse visual assets with identity or memory.** Lila's personality, conversation history, memory and model routing belong to Lila Core; the Live2D model is a client-side visual representation.

## MVP acceptance criteria

The fixed-appearance avatar milestone is complete only when:
- one approved Live2D model loads reliably on Windows 11 x64;
- the avatar renders in a transparent desktop overlay without a persistent background rectangle;
- the avatar can be moved and repositioned without moving or focusing the chat window;
- idle animation and the agreed initial motion/pose set work;
- supported expressions respond to validated controls/Core events;
- the avatar remains visible when chat is minimized and follows the defined lock/suspend lifecycle;
- the same fixed appearance is restored after restart;
- unsupported events and asset references are rejected without corrupting the current render;
- no appearance generation or appearance-changing operation is required for acceptance.

The overall Galaxy + Lila Core + Lila Desktop MVP is not complete until the shared conversation/context path is verified across Android and Desktop. A locally animated demo avatar alone is not evidence of ecosystem integration.

## Scope rule

During A0–A5, prioritize the first stable, integrated working system over appearance customization. Revisit A6 only after the user-visible avatar, shared Core conversation and cross-device behavior have passed their acceptance checks.
