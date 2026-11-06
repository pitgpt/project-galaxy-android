# Realtime voice session security

## Decision

The Android application must never contain a long-lived OpenAI API key. Galaxy obtains a short-lived Realtime client credential from a trusted backend, then uses that credential to establish its voice session with OpenAI.

This is both a security boundary and an operational boundary: the backend can rotate or revoke the server key, enforce quotas, and change provider configuration without shipping a new APK.

## Trust boundaries

1. **Galaxy Android** is an untrusted client. APK contents, local storage, and traffic available to the device owner must be treated as inspectable.
2. **Galaxy backend** is trusted with the long-lived provider key. The key is loaded from the deployment secret store, never from source control or client configuration.
3. **OpenAI Realtime** receives the ephemeral client credential and serves the live session.

The Android app must not receive, persist, log, or attempt to reconstruct the long-lived provider key.

## Proposed session flow

1. The app authenticates to the Galaxy backend using the application's own user/session authentication mechanism.
2. The app requests a voice session from `POST /v1/realtime/sessions`.
3. The backend validates the caller, applies per-user and global rate limits, resolves the requested character/voice against server-owned allow-lists, derives a stable privacy-preserving safety identifier for the authenticated user, and creates a Realtime client secret using its server-side provider key.
4. The backend returns only the short-lived client credential and the minimum session metadata required by the client. It never returns the server key.
5. Android uses the short-lived credential to establish the Realtime connection over WebRTC.
6. When the credential expires or the session ends, the client requests a new session credential. It does not retry indefinitely.

## Backend API contract (initial)

Request:

- Method/path: `POST /v1/realtime/sessions`
- Authentication: required Galaxy user/session credential.
- Body: a versioned, allow-listed session configuration. Character IDs are identifiers, not trusted instructions; the backend resolves permitted model, voice, and session limits.

Successful response:

- `client_secret`: the short-lived client secret (sensitive; memory-only on Android).
- `expires_at`: Unix timestamp in seconds, converted to milliseconds at the Android boundary.
- `session_id`: non-secret Realtime session identifier.
- Only non-secret session metadata needed by the WebRTC client.

The backend calls OpenAI `POST /v1/realtime/client_secrets` using its server-side API key. It sets an explicit short TTL with `expires_after` (`anchor: created_at`; `seconds` must be between 10 and 7200) and a server-controlled `session` object with `type: realtime`. The default TTL is 600 seconds, but Galaxy should choose the shortest TTL compatible with its connection setup. The secret expiry limits when a session can be created; it does not terminate a session that has already started. The Android app must treat the returned secret as single-session material even though the provider token may technically be reusable until expiry.

Errors:

- `401` unauthenticated, `403` not permitted, `429` rate limited, `5xx` temporary provider/backend failure.
- Error bodies must be safe for display and must not include provider keys, raw authorization headers, or internal secrets.

Provider integration is pinned to the current GA Realtime client-secret endpoint (`POST /v1/realtime/client_secrets`), not the legacy beta session-token endpoint. Android should receive a Galaxy-owned response DTO, not the raw provider response. The backend must construct the provider session configuration from server-side policy; client-supplied model, instructions, tools, and arbitrary session fields are not forwarded.

## Security requirements

- Store the provider key only in the backend deployment's secret manager/environment injection.
- Require TLS for app-to-backend and app-to-provider connections.
- Authenticate every session-creation request; do not expose an unauthenticated token-minting proxy.
- Apply per-account rate limits, concurrency limits, and usage ceilings before minting credentials.
- Set `OpenAI-Safety-Identifier` on the server-side client-secret creation request using a stable, privacy-preserving value derived from the authenticated account. Never use raw email addresses or other directly identifying values.
- Allow-list model, voice, tools, and session parameters on the backend. Never accept arbitrary provider configuration from the client.
- Do not log client credentials, provider keys, authorization headers, or SDP bodies containing credentials.
- Keep ephemeral credentials in memory only; do not write them to Room, preferences, analytics, crash reports, or debug logs.
- Handle expiry, network loss, and provider rejection explicitly. Avoid infinite automatic reconnect loops.
- Provide server-side revocation/rotation for the long-lived key and a kill switch for session creation.

## Android architecture boundary

The Android domain layer should depend on an abstraction such as a session-credential provider, not on an OpenAI secret or a provider-specific permanent key. A future implementation can call the Galaxy backend and return a typed, expiring credential to the WebRTC transport layer.

The voice transport should remain separate from character persistence and conversation history. Character profiles may select an allow-listed voice preference, but they must not carry credentials.

## Delivery sequence

1. **Now:** record this security boundary and API contract before introducing networking dependencies.
2. Add backend service and deployment secret configuration.
3. Implement authenticated, rate-limited session creation and provider payload validation.
4. Add Android backend client and typed expiring-credential model.
5. Add WebRTC transport, connection lifecycle, audio permissions, and explicit reconnect handling.
6. Add integration tests for authentication, expiry, rate limits, key non-disclosure, and provider failures.

No live voice connection is claimed to exist until these steps are implemented and tested.
