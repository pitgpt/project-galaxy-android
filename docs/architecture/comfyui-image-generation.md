# ComfyUI image generation architecture

## Goal

Provide an optional image-generation capability through ComfyUI while keeping the core chat experience provider-neutral and resilient when ComfyUI is unavailable.

## Boundaries

- ComfyUI is an image-generation provider, not a source of character identity or memory.
- Use versioned workflow templates with explicit input schemas and validation.
- Keep endpoint configuration and credentials separate from prompts and generated assets.
- Do not transmit private memories or full conversation history by default. The user explicitly chooses the prompt and references sent to a workflow.
- Generated images are assets with stable IDs, provenance, workflow version, creation time, and local storage location.

## Job lifecycle

Model jobs as queued, running, succeeded, failed, cancelled, or unknown.
- Persist opaque provider job IDs and local request correlation IDs.
- Reconcile state after process death and network loss.
- Cancellation must verify provider state and must not mark a completed job as cancelled.
- Retrying must not accidentally submit duplicate jobs when a prior submission may have succeeded.
- Keep image bytes out of job metadata tables.

## Storage and privacy

- Store generated assets through the encrypted media-storage layer once available.
- Until encrypted media storage exists, do not claim generated images are protected by SQLCipher merely because job metadata is encrypted.
- Support user deletion of generated assets and associated metadata.
- Never log credentials or full sensitive prompt payloads.

## Suggested implementation slices

1. Audit the existing ComfyUI adapter and durable job-store work before adding another implementation.
2. Align job persistence with the SQLCipher migration chain.
3. Add versioned workflow templates and validated parameter models.
4. Add generation UI, progress, cancellation, and retry/reconciliation.
5. Add encrypted asset storage and gallery management.

## Acceptance criteria

- Chat remains usable if ComfyUI is offline.
- Duplicate submission is prevented or explicitly surfaced as uncertain.
- Job state recovers after app process death.
- Cancellation does not overwrite a completed state.
- Workflow inputs are validated and secrets are redacted.
- Generated assets have provenance and a working delete path.
