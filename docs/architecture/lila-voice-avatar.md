# Lila voice and avatar architecture

## Goal

Add optional voice interaction and an expressive avatar without coupling either capability to a specific language model or making text chat dependent on media services.

## Voice boundaries

- A character profile selects a voice by provider-neutral voice ID and stores user-facing preferences such as speaking rate and preferred language.
- A speech provider exposes capability discovery, synthesis or realtime session creation, cancellation, health status, and structured errors.
- Keep speech-to-text, text-to-speech, and realtime duplex conversation as distinct capabilities; providers may implement any subset.
- Audio permissions, microphone state, network state, and session lifecycle belong to the runtime, not the character identity.
- Streaming playback must support interruption, cancellation, and graceful return to text-only chat.
- Secrets and provider credentials are held by the secure provider settings layer; never put them in profile JSON or logs.

## Emotional delivery

- Represent delivery as bounded presentation hints (pace, pause, emphasis, prosody), not as claims about internal human feelings.
- Stable character behavior remains in the profile. Temporary conversational affect is ephemeral runtime state with expiry.
- User voice and accessibility settings take precedence over automatically selected delivery hints.
- If a provider cannot express a hint, ignore it safely rather than failing the response.

## Avatar boundaries

- Define an asset manifest with stable asset ID, format, dimensions, provenance/license, preview, and supported states.
- First milestone is a static portrait with deterministic states: idle, listening, thinking, speaking.
- Expressions are driven by explicit UI/runtime events; do not infer sensitive traits from the user's voice or image.
- Animation is optional, battery-aware, lifecycle-aware, and falls back to a static image when unsupported.
- Keep avatar rendering independent from chat message storage and model provider selection.

## Suggested implementation slices

1. Add provider-neutral voice capability contracts and fake-provider tests.
2. Add per-character voice selection and user settings UI.
3. Add playback/session lifecycle, interruption, permission, and text fallback.
4. Add avatar manifest and static portrait rendering.
5. Add deterministic expression state machine.
6. Add optional animation after lifecycle and battery tests.

## Acceptance criteria

- Text chat remains fully functional with no microphone permission and no voice provider.
- A provider failure never loses the text response or conversation history.
- Voice selection persists per character and survives app restart.
- Playback and recording can be stopped reliably when leaving a conversation.
- Avatar assets have documented provenance and a static fallback.
- Runtime tests cover permission denial, provider failure, interruption, and background/foreground transitions.
