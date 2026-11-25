# Local model provider architecture

## Goal

Allow Galaxy to connect to local inference runtimes without making character identity, memory, or chat history depend on a particular runtime.

## Provider contract

A model provider should expose:
- provider ID and display metadata;
- endpoint configuration and connection health;
- model discovery where supported;
- capability metadata (chat, streaming, vision, tool use, context window);
- request submission with cancellation;
- streaming chunks and normalized completion/error events;
- model-specific options validated against declared capabilities.

The app must distinguish a local endpoint from a cloud endpoint and show the selected route clearly.

## Security and privacy

- Endpoint configuration is user-controlled and opt-in.
- Store credentials separately in the secure provider settings layer, never in model profiles, logs, crash reports, or repository files.
- Do not silently expose a local server to the public network. Document binding to loopback/LAN and require deliberate configuration for remote access.
- Never send long-term memories or full conversation history to a provider beyond the context explicitly assembled for the current request.
- Support a provider connection test that does not transmit conversation content.

## Runtime behavior

- Provider switching must not change character ID, memory ownership, or conversation persistence.
- Handle unavailable endpoint, unsupported model, context overflow, timeout, cancellation, and malformed stream as distinct recoverable errors.
- Preserve partial assistant output and allow retry without duplicating the user message.
- Capability negotiation determines whether image input, tools, or streaming controls are shown.

## Suggested implementation slices

1. Introduce provider capability and health models.
2. Implement one local OpenAI-compatible endpoint adapter.
3. Add endpoint/model settings and connection test.
4. Add streaming, cancellation, and error normalization.
5. Add optional adapters for other local runtimes only after contract tests pass.

## Acceptance criteria

- Existing cloud providers continue working unchanged.
- Local endpoint settings survive restart without leaking secrets.
- Switching providers preserves conversation and character identity.
- Tests cover streaming, cancellation, timeout, malformed responses, and unavailable endpoints.
