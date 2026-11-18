# Lila Core foundation

Status: initial architecture and API contract
Branch: `feature/lila-core-foundation`

## Scope

Lila Core is the platform-independent conversation and identity layer. Galaxy Android and Lila Desktop are clients. This first increment defines stable, validated contracts only; it does not yet add an LLM provider, persistent memory, or a new public endpoint.

## Existing Galaxy foundation

The current Android app is a single Gradle module with platform-independent domain models under `core/model`, repository contracts under `core/repository`, and Room-backed persistence. The separate FastAPI service currently owns authenticated short-lived Realtime credentials, JWT validation, quotas, and session leases.

The Core foundation must coexist with those components. In particular, do not put provider API keys in Android/Desktop clients, do not bypass the existing authentication boundary, and do not treat the current local Room database as encrypted.

## First contract boundary

The initial Python package is `galaxy_backend/lila_core`. It defines:
- a normalized conversation-turn request;
- a response containing assistant text and zero or more typed avatar events;
- a versioned avatar event envelope;
- a constrained emotion vocabulary.

The contract deliberately contains no provider-specific model fields. A later application service will call a configured model adapter and memory interface, then map the result to these contracts.

## Planned increments

1. Contract tests and validation.
2. Provider-neutral model adapter interface and deterministic fake adapter.
3. Context assembly and explicit memory interface.
4. Persistence implementation selected after auditing existing Android/backend storage.
5. Authenticated API integration.
6. Desktop and Android client adapters.

## Non-goals for this increment

- No production deployment.
- No automatic memory extraction or deletion.
- No direct model-to-avatar execution.
- No change to the existing Realtime session endpoint.
- No changes to `main`.
