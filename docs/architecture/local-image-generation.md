# Local Image Generation — Architecture Plan

Status: proposed implementation plan; no runtime code or database schema changes yet.

## Product goal

Allow a user to request an image naturally inside an ongoing Galaxy conversation (for example, “show your current avatar”). Galaxy preserves conversation context, creates a structured image-generation request, sends it to a configurable image engine, and inserts the resulting image into the same conversation timeline.

Text, voice, memory, and images are parts of one continuous conversation. Image generation must not be a detached utility screen.

## Architecture boundaries

1. **ImagePromptComposer (domain)**
   - Input: character profile, recent conversation context, relevant approved memories, user request, and optional reference asset identifiers.
   - Output: structured ImageGenerationRequest, not a provider-specific payload.
   - Preserve stable character traits separately from scene-specific traits.
   - Keep the composed prompt and generation settings as auditable metadata associated with the generated asset.
   - Do not silently invent personal facts or claim the image depicts a real current physical appearance.

2. **ImageGenerationProvider (domain interface)**
   - Provider-neutral suspend API for capability discovery, submission, progress/status, cancellation, and result retrieval.
   - Provider implementations remain independent from chat UI and text providers.
   - Initial implementation: ComfyUiImageGenerationProvider.
   - Future implementations may include other local engines or cloud image APIs without changing conversation orchestration.

3. **ComfyUI integration (data layer)**
   - Connect to a user-configured ComfyUI endpoint on the user's own trusted network.
   - Support workflow templates with validated inputs (checkpoint, LoRA, dimensions, sampler, steps, seed, reference images).
   - Submit workflow, poll execution status, retrieve output images and metadata.
   - Handle timeout, cancellation, engine unavailable, out-of-memory, and malformed workflow responses.
   - Never require exposing ComfyUI directly to the public internet. Prefer LAN/VPN access and authenticated pairing; warn on insecure HTTP except explicitly trusted local-network configuration.
   - Do not store API credentials in plaintext.

4. **Conversation media model**
   - Extend conversation messages with typed content/attachments rather than overloading the existing text content field.
   - Represent generation lifecycle: queued, running, completed, failed, cancelled.
   - Store generated media in app-managed storage; database rows contain stable asset IDs and metadata, not large image blobs.
   - Keep a reference from the assistant message to the generated asset and to the generation request.
   - Existing text messages must remain readable and migratable.

5. **Chat UI**
   - User can request generation in ordinary language or use an explicit image action.
   - Show progress and allow cancellation.
   - Render generated images inline in the conversation, with retry, save/share, and use-as-reference actions.
   - Preserve accessibility descriptions and loading/error states.

6. **Character continuity**
   - Maintain a versioned character profile with invariant identity traits and separately editable scene/style attributes.
   - Support reference-image conditioning through engine capabilities (for example, IP-Adapter or equivalent), rather than assuming every workflow supports it.
   - LoRA training and character-specific fine-tuning are a later phase after the basic pipeline is stable.
   - For Lila's profile, use an explicitly adult woman design and the user's approved stable visual references; scene details may vary without changing identity.

## Suggested domain models

- ImageGenerationRequest: request ID, conversation/character IDs, user prompt, composed prompt, optional negative prompt, workflow ID/version, dimensions, seed, reference asset IDs, created timestamp.
- ImageGenerationStatus: queued/running/completed/failed/cancelled plus safe user-facing detail.
- GeneratedImageAsset: asset ID, local URI/path abstraction, MIME type, dimensions, byte size, checksum, created timestamp, generation request ID.
- ImageGenerationCapabilities: supported dimensions, formats, workflow parameters, reference conditioning, progress reporting, cancellation.

Exact Kotlin types and persistence schema should be finalized after the encrypted Room database PR is integrated and its migration path is stable.

## Delivery phases

### Phase A — contract and provider skeleton
- Add domain request/status/capability models and provider interface.
- Add fake provider and unit tests for orchestration, cancellation, failure, and lifecycle.
- No database migration or UI replacement in this phase.

### Phase B — ComfyUI proof of connection
- Add endpoint configuration and safe pairing/connection test.
- Validate one known workflow against a local ComfyUI instance.
- Return generated image bytes/metadata through a repository abstraction.

### Phase C — persistence and inline chat
- Design Room migration on top of the encrypted database baseline.
- Add media asset storage and typed conversation attachments.
- Render image messages inline with progress, retry, cancellation, and save/share.

### Phase D — context-aware prompt composition
- Compose prompts from character profile, recent messages, and scoped memories under explicit privacy/token-budget rules.
- Keep the final prompt visible/editable for user transparency.
- Add stable character references and workflow presets.

### Phase E — continuity and optimization
- Add reference conditioning and optional character LoRA workflows.
- Add image caching, cleanup controls, storage quotas, and performance telemetry.

## Non-goals for the first release

- Training LoRA inside the Android app.
- Embedding multi-gigabyte image models in the APK.
- Exposing a home PC generation server publicly without authentication.
- Changing or weakening existing text-provider, memory, or encrypted-database guarantees.
- Treating image generation as a separate chat or losing its link to conversation context.

## Acceptance criteria for the first usable release

- A user can request an image from the existing chat.
- Galaxy composes a request using the active character and current conversation context.
- ComfyUI receives the workflow and returns an image.
- The image appears inline in the same conversation and survives app restart.
- The request can be cancelled; failures are recoverable and do not corrupt the conversation.
- Existing text history and encrypted local data remain intact.
- The user can inspect the prompt and generation metadata.