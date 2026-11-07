# Text provider architecture

## Current boundary

Text generation is exposed through the `TextChatProvider` interface in the data provider layer. The application use case `GenerateCharacterReply` depends on this interface rather than a concrete HTTP client.

The initial implementation is `OpenAiCompatibleChatClient`, which implements OpenAI-compatible Chat Completions over HTTPS. Its HTTP transport is separately injectable so request behavior can be tested without live credentials or network access.

## Configuration and secret handling

Provider definitions are stored locally by `EncryptedProviderStore`. API keys are encrypted with AES-GCM using a non-exportable Android Keystore key. The encrypted preferences file is excluded from cloud backup and device transfer.

Provider API keys must never be placed in source control, Android build configuration, logs, crash reports, or conversation history. Provider error messages must remain sanitized and must not echo response bodies or authorization data.

## Text and voice are separate

The text provider contract does not own voice sessions, WebRTC, microphone capture, audio playback, or realtime session credentials. Those remain behind the independent realtime voice/session abstractions.

A provider may eventually offer both text and voice, but Galaxy must configure and select those capabilities independently. A text-only API key must not be assumed to authorize realtime voice.

## Adding another text backend

A future adapter (for example native Anthropic, Gemini, or a local HTTP server) should:

1. Implement `TextChatProvider`.
2. Translate Galaxy's role-based `ChatTurn` list into that provider's request format.
3. Validate the endpoint and use HTTPS for remote providers; local-network exceptions require an explicit, separately designed trust policy.
4. Keep credentials out of logs and exception messages.
5. Bound response size and request time, disable unsafe redirects, and support coroutine cancellation.
6. Add unit tests using an injected fake transport.
7. Preserve the same conversation history and character identity when the selected adapter changes.

Provider-specific request fields should remain inside the adapter. Core use cases should not branch on vendor names or API schemas.

## Later work

- Introduce explicit provider capability metadata and model discovery.
- Support per-provider model parameters and context-window limits.
- Add native vendor adapters and a local-model adapter.
- Add streaming responses through a separate streaming contract rather than complicating the simple completion API.
- Add user-facing export, review, and deletion controls for conversation and memory data.
