# Lila capability architecture

## Status

Initial design baseline. This document describes the intended boundaries and delivery sequence; it does not claim that the features below are implemented.

## Existing Galaxy contracts

The current application already has:
- `CharacterProfile` with `personaDefinition`, `avatarId`, `voiceId`, and `modelProviderId`;
- `MemoryItem` with explicit `USER`, `CHARACTER`, and `SHARED` scopes;
- conversation messages associated with a `characterId`;
- repository interfaces for characters, conversations, and memories;
- a provider-neutral character model.

New capabilities should extend these contracts rather than create a parallel identity or memory system.

## Product principles

1. **Lila is a character profile, not a hard-coded app persona.** The built-in Lila profile is editable through versioned settings while the Galaxy engine remains provider-neutral.
2. **Separate identity from runtime state.** Stable personality, communication style, boundaries, and user-approved preferences belong to the character configuration. Temporary mood/affect belongs to an expiring runtime state and must never silently rewrite identity.
3. **Memory is explicit and inspectable.** Every memory has an owner scope, provenance, timestamps, confidence/importance, and lifecycle. The user can inspect, edit, export, and delete memories.
4. **No hidden sensitive inference.** Do not create or retain sensitive personal memories without explicit user intent. Never store credentials, API keys, or authentication tokens as memories.
5. **Provider independence.** Text, speech, image generation, and local inference use interfaces with provider adapters; switching providers must not change character identity or memory ownership.
6. **Graceful degradation.** If a voice, avatar renderer, local model, or ComfyUI endpoint is unavailable, text chat and existing history remain usable.
7. **Device-first privacy.** Character profiles, conversations, and memories remain device-local by default. Cross-device sync requires a separate, explicit encrypted export/import design.

## Capability boundaries

### Character identity and behavior

Introduce a versioned character configuration layered over `CharacterProfile`:
- identity: display name, description, persona definition, language;
- behavior: warmth, directness, initiative, humor, verbosity, response pacing;
- relationship/context preferences explicitly configured by the user;
- boundaries and safety behavior;
- prompt assembly policy with deterministic precedence: platform rules, app policy, character definition, user preferences, conversation context, retrieved memories.

User-editable settings must be data, not executable prompt fragments. Validate lengths and allowed values. Keep a version and migration path.

### Long-term memory and recollections

Extend the existing `MemoryItem` model through a migration-safe memory envelope:
- stable ID, scope and owner character;
- content and concise summary;
- source type (user-stated, user-confirmed, conversation-derived);
- confidence and importance;
- created/updated/last-accessed timestamps;
- optional expiry and supersession link.

Memory retrieval must be bounded and scoped to the active character plus explicitly shared user memories. Conversation-derived candidates should remain pending until confirmed or governed by a clearly visible opt-in policy. Provide memory review, correction, deletion, and export controls before calling the feature complete.

### Voice and emotional expression

Keep speech synthesis and realtime conversation behind provider interfaces. Voice settings belong to the character profile; transient delivery controls (pace, pause, emphasis, prosody) are separate from emotional state. Emotional expression should influence delivery only within configured bounds and must not imply that the system has human feelings or override user-selected voice settings.

### Avatar and animation

Define an avatar asset manifest independent of the chat UI:
- asset ID, source/format, license/provenance, preview;
- supported expressions and animation states;
- fallback static image;
- renderer capability declaration.

Start with a static portrait and a small set of deterministic states (idle, speaking, listening, thinking). Animation must be optional and battery-aware.

### Local model providers

Define a provider contract for model discovery, capability metadata, context limits, streaming, cancellation, and health checks. Store endpoint configuration separately from secrets. API keys and tokens must use secure secret storage and must never be committed or embedded in the APK. Local endpoints should be opt-in and network access constrained to explicitly configured hosts.

### ComfyUI image generation

Treat ComfyUI as an image-generation provider, not as part of character identity. Keep workflow templates versioned, validate inputs, persist job correlation, support cancellation/reconciliation, and store generated assets through the encrypted media-storage layer. Do not send private memories or full conversation history to workflows by default.

## Proposed delivery slices

1. **Lila profile foundation** — built-in profile seed, versioned behavior settings, repository/use-case tests, and a minimal settings UI.
2. **Memory lifecycle** — explicit provenance, scoped retrieval, user review/edit/delete/export, and migration tests.
3. **Provider contracts** — common text-model and speech interfaces with provider capability metadata.
4. **Voice experience** — selectable voice, permissions, connection lifecycle, interruption and fallback tests.
5. **Avatar runtime** — asset manifest, static portrait, expression state machine, then optional animation.
6. **Local inference** — provider adapter, endpoint configuration, health checks, streaming and cancellation.
7. **ComfyUI experience** — workflow selection, safe parameter validation, durable job status, encrypted output storage.

## Cross-cutting acceptance criteria

- Existing conversations and character profiles survive schema migration.
- A character's memories cannot leak into another character's context unless explicitly shared.
- Memory can be reviewed, corrected, exported, and deleted.
- Secrets never appear in logs, prompts, crash reports, or repository files.
- Provider failure never destroys conversation history.
- Unit tests cover prompt precedence, memory scope, and provider fallback.
- Instrumentation tests cover encrypted persistence and process restart.
- Each slice is delivered on an isolated feature branch with a Draft PR; no direct changes to `main`.
