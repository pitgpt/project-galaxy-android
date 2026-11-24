import asyncio
from uuid import uuid4

import pytest
from fastapi import HTTPException
from fastapi.testclient import TestClient

from galaxy_backend.config import Settings
from galaxy_backend.lila_core.context import ContextAssembler
from galaxy_backend.lila_core.contracts import CoreTurnResponse
from galaxy_backend.lila_core.memory import ConversationMessage, MemorySnippet
from galaxy_backend.lila_core.openai_compatible import (
    ModelProviderConfigurationError,
    ModelProviderUnavailable,
)
from galaxy_backend.lila_core.service import CoreTurnService, ModelAdapterContractError
from galaxy_backend.lila_core.sqlite_store import SqliteLilaStore
from galaxy_backend.main import create_app


STORE_KEY = bytes.fromhex("3031323334353637383961626364656630313233343536373839616263646566")


class FakeAuthenticator:
    def authenticate(self, token: str) -> str:
        identities = {
            "user-a-token": "user-a",
            "user-b-token": "user-b",
        }
        if token not in identities:
            raise HTTPException(status_code=401, detail="Invalid or expired access token")
        return identities[token]


class FakeHttpClient:
    async def aclose(self):
        pass


class RecordingTurnLimiter:
    def __init__(self, error=None):
        self.error = error
        self.calls = []

    async def check(self, principal_id):
        self.calls.append(principal_id)
        if self.error is not None:
            raise self.error


class RecordingHistoryReader:
    def __init__(self):
        self.calls = []
        self.appended_turns = []
        self.model_identities = []

    async def load_recent_messages(self, *, principal_id, conversation_id, limit):
        self.calls.append((principal_id, conversation_id, limit))
        return [
            ConversationMessage(
                role="assistant",
                content=f"history:{principal_id}",
            )
        ]

    async def append_turn(
        self, *, principal_id, conversation_id, user_message, assistant_message,
        provider_id=None, model_id=None, fallback_from_provider_id=None,
        fallback_from_model_id=None, fallback_reason=None
    ):
        self.appended_turns.append((principal_id, conversation_id, user_message, assistant_message))
        self.model_identities.append((provider_id, model_id))


class RecordingMemoryReader:
    def __init__(self):
        self.calls = []

    async def search_relevant_memories(self, *, principal_id, query, limit):
        self.calls.append((principal_id, query, limit))
        return [
            MemorySnippet(
                memory_id=f"memory-{principal_id}",
                content=f"memory:{principal_id}",
            )
        ]


class RecordingModelAdapter:
    def __init__(self, *, error=None, response=None):
        self.error = error
        self.response = response
        self.contexts = []

    async def generate(self, context):
        self.contexts.append(context)
        if self.error is not None:
            raise self.error
        if self.response is not None:
            return self.response
        return CoreTurnResponse(
            conversation_id=context.conversation_id,
            text="Ответ Лилы",
            emotion="happy",
            provider_id="test-provider",
            model_id="test-model",
            avatar_events=[
                {
                    "type": "avatar.emotion",
                    "payload": {"emotion": "happy", "intensity": 0.75},
                }
            ],
        )



class RecordingProviderAdapter:
    provider_id = "operator-configured"
    model_id = "configured-model"

    def __init__(self, *, models=("model-a", "model-b"), error=None):
        self.models = models
        self.error = error
        self.calls = 0

    async def discover_models(self):
        self.calls += 1
        if self.error is not None:
            raise self.error
        return self.models


def make_settings():
    return Settings(
        openai_api_key="sk-test-only",
        auth_jwks_url="https://issuer.example/.well-known/jwks.json",
        auth_issuer="https://issuer.example/",
        auth_audience="galaxy-android",
        safety_identifier_hmac_key="test-hmac-secret-long-enough",
        allowed_voices=frozenset({"alloy"}),
        allowed_character_ids=frozenset({"galaxy.default"}),
    )


def build_service(*, adapter=None):
    history = RecordingHistoryReader()
    memories = RecordingMemoryReader()
    model = adapter or RecordingModelAdapter()
    assembler = ContextAssembler(history, memories)
    service = CoreTurnService(assembler, model, history)
    return service, history, memories, model


def make_client(service=None, turn_limiter=None, history_reader=None, provider_adapter=None):
    app = create_app(
        settings=make_settings(),
        http_client=FakeHttpClient(),
        authenticator=FakeAuthenticator(),
        lila_core_service=service,
        lila_turn_rate_limiter=turn_limiter,
        lila_history_reader=history_reader,
        lila_provider_adapter=provider_adapter,
    )
    return TestClient(app)


def turn_payload(**extra):
    return {
        "conversation_id": "conversation-1",
        "message": "Привет, Лила",
        **extra,
    }


def test_lila_turn_requires_bearer_token():
    service, history, memories, adapter = build_service()
    limiter = RecordingTurnLimiter()
    with make_client(service, limiter) as client:
        response = client.post("/v1/lila/turns", json=turn_payload())
    assert response.status_code == 401
    assert limiter.calls == []
    assert history.calls == []
    assert memories.calls == []
    assert adapter.contexts == []


def test_lila_turn_rejects_invalid_bearer_token():
    service, history, memories, adapter = build_service()
    with make_client(service) as client:
        response = client.post(
            "/v1/lila/turns",
            headers={"Authorization": "Bearer invalid-token"},
            json=turn_payload(),
        )
    assert response.status_code == 401
    assert history.calls == []
    assert memories.calls == []
    assert adapter.contexts == []


def test_lila_turn_uses_jwt_subject_and_returns_typed_avatar_events():
    service, history, memories, adapter = build_service()
    limiter = RecordingTurnLimiter()
    with make_client(service, limiter) as client:
        response = client.post(
            "/v1/lila/turns",
            headers={"Authorization": "Bearer user-a-token"},
            json=turn_payload(),
        )

    assert response.status_code == 200
    body = response.json()
    assert body["conversation_id"] == "conversation-1"
    assert body["text"] == "Ответ Лилы"
    assert body["emotion"] == "happy"
    assert body["provider_id"] == "test-provider"
    assert body["model_id"] == "test-model"
    assert history.model_identities == [("test-provider", "test-model")]
    assert body["avatar_events"][0]["type"] == "avatar.emotion"
    assert limiter.calls == ["user-a"]
    assert history.calls == [("user-a", "conversation-1", 20)]
    assert memories.calls == [("user-a", "Привет, Лила", 8)]
    assert history.appended_turns == [
        ("user-a", "conversation-1", "Привет, Лила", "Ответ Лилы")
    ]
    assert adapter.contexts[0].messages[-1].content == "Привет, Лила"
    assert adapter.contexts[0].relevant_memories[0].content == "memory:user-a"


def test_client_cannot_supply_or_override_principal_id():
    service, history, memories, adapter = build_service()
    with make_client(service) as client:
        response = client.post(
            "/v1/lila/turns",
            headers={"Authorization": "Bearer user-a-token"},
            json=turn_payload(principal_id="user-b"),
        )
    assert response.status_code == 422
    assert history.calls == []
    assert memories.calls == []
    assert adapter.contexts == []


def test_same_conversation_id_is_scoped_by_authenticated_user():
    service, history, memories, adapter = build_service()
    with make_client(service) as client:
        first = client.post(
            "/v1/lila/turns",
            headers={"Authorization": "Bearer user-a-token"},
            json=turn_payload(),
        )
        second = client.post(
            "/v1/lila/turns",
            headers={"Authorization": "Bearer user-b-token"},
            json=turn_payload(),
        )

    assert first.status_code == second.status_code == 200
    assert history.calls == [
        ("user-a", "conversation-1", 20),
        ("user-b", "conversation-1", 20),
    ]
    assert memories.calls == [
        ("user-a", "Привет, Лила", 8),
        ("user-b", "Привет, Лила", 8),
    ]
    assert history.appended_turns == [
        ("user-a", "conversation-1", "Привет, Лила", "Ответ Лилы"),
        ("user-b", "conversation-1", "Привет, Лила", "Ответ Лилы"),
    ]
    assert adapter.contexts[0].messages[0].content == "history:user-a"
    assert adapter.contexts[1].messages[0].content == "history:user-b"
    assert adapter.contexts[0].relevant_memories[0].content == "memory:user-a"
    assert adapter.contexts[1].relevant_memories[0].content == "memory:user-b"


def test_lila_turn_fails_closed_when_service_is_not_configured():
    with make_client() as client:
        response = client.post(
            "/v1/lila/turns",
            headers={"Authorization": "Bearer user-a-token"},
            json=turn_payload(),
        )
    assert response.status_code == 503
    assert response.json()["detail"] == "Lila Core is not configured"


def test_provider_failure_is_sanitized_at_api_boundary():
    service, _, _, _ = build_service(
        adapter=RecordingModelAdapter(error=RuntimeError("provider-secret-detail"))
    )
    with make_client(service) as client:
        response = client.post(
            "/v1/lila/turns",
            headers={"Authorization": "Bearer user-a-token"},
            json=turn_payload(),
        )
    assert response.status_code == 503
    assert "provider-secret-detail" not in response.text
    assert response.json()["detail"] == "Lila Core is temporarily unavailable"


def test_invalid_provider_output_is_sanitized_as_bad_gateway():
    service, _, _, _ = build_service(
        adapter=RecordingModelAdapter(
            response={
                "conversation_id": "conversation-1",
                "text": "invalid",
                "unexpected": True,
            }
        )
    )
    with make_client(service) as client:
        response = client.post(
            "/v1/lila/turns",
            headers={"Authorization": "Bearer user-a-token"},
            json=turn_payload(),
        )
    assert response.status_code == 502
    assert response.json()["detail"] == "Lila model returned an invalid response"


def test_provider_response_for_another_conversation_is_sanitized():
    service, _, _, _ = build_service(
        adapter=RecordingModelAdapter(
            response=CoreTurnResponse(
                conversation_id="another-conversation",
                text="wrong conversation",
            )
        )
    )
    with make_client(service) as client:
        response = client.post(
            "/v1/lila/turns",
            headers={"Authorization": "Bearer user-a-token"},
            json=turn_payload(),
        )
    assert response.status_code == 502
    assert response.json()["detail"] == "Lila model returned an invalid response"



def test_lila_turn_rate_limit_rejects_before_context_or_model():
    service, history, memories, adapter = build_service()
    limiter = RecordingTurnLimiter(
        error=HTTPException(
            status_code=429,
            detail="Lila turn rate limit exceeded",
            headers={"Retry-After": "60"},
        )
    )
    with make_client(service, limiter) as client:
        response = client.post(
            "/v1/lila/turns",
            headers={"Authorization": "Bearer user-a-token"},
            json=turn_payload(),
        )

    assert response.status_code == 429
    assert response.headers["Retry-After"] == "60"
    assert limiter.calls == ["user-a"]
    assert history.calls == []
    assert memories.calls == []
    assert adapter.contexts == []


def test_lila_turn_rate_limiter_backend_failure_is_sanitized():
    service, history, memories, adapter = build_service()
    limiter = RecordingTurnLimiter(error=RuntimeError("redis-password-detail"))
    with make_client(service, limiter) as client:
        response = client.post(
            "/v1/lila/turns",
            headers={"Authorization": "Bearer user-a-token"},
            json=turn_payload(),
        )

    assert response.status_code == 503
    assert "redis-password-detail" not in response.text
    assert response.json()["detail"] == "Lila turn service temporarily unavailable"
    assert history.calls == []
    assert memories.calls == []
    assert adapter.contexts == []



def test_production_requires_shared_redis_for_lila_turn_limits():
    from dataclasses import replace

    settings = replace(make_settings(), environment="production")
    app = create_app(
        settings=settings,
        http_client=FakeHttpClient(),
        authenticator=FakeAuthenticator(),
        rate_limiter=object(),
        lila_turn_rate_limiter=RecordingTurnLimiter(),
    )

    with pytest.raises(
        RuntimeError,
        match="Production requires Redis-backed Lila turn rate limiting",
    ):
        with TestClient(app):
            pass



def build_persistent_service(database_path, *, adapter=None):
    store = SqliteLilaStore(database_path, STORE_KEY)
    model = adapter or RecordingModelAdapter()
    service = CoreTurnService(ContextAssembler(store, store), model, store)
    return service, store, model


def test_idempotency_replays_the_committed_response_without_calling_model_twice(tmp_path):
    service, store, adapter = build_persistent_service(tmp_path / "lila.sqlite3")
    key = str(uuid4())
    headers = {
        "Authorization": "Bearer user-a-token",
        "Idempotency-Key": key,
    }

    with make_client(service) as client:
        first = client.post("/v1/lila/turns", headers=headers, json=turn_payload())
        replay = client.post("/v1/lila/turns", headers=headers, json=turn_payload())

    assert first.status_code == replay.status_code == 200
    assert first.json() == replay.json()
    assert len(adapter.contexts) == 1
    history = asyncio.run(store.load_recent_messages(
        principal_id="user-a", conversation_id="conversation-1", limit=10
    ))
    assert [(item.role, item.content) for item in history] == [
        ("user", "Привет, Лила"),
        ("assistant", "Ответ Лилы"),
    ]


def test_idempotency_key_cannot_be_reused_for_a_different_message(tmp_path):
    service, store, adapter = build_persistent_service(tmp_path / "lila.sqlite3")
    headers = {
        "Authorization": "Bearer user-a-token",
        "Idempotency-Key": str(uuid4()),
    }

    with make_client(service) as client:
        first = client.post("/v1/lila/turns", headers=headers, json=turn_payload())
        conflict = client.post(
            "/v1/lila/turns",
            headers=headers,
            json=turn_payload(message="Совсем другое сообщение"),
        )

    assert first.status_code == 200
    assert conflict.status_code == 409
    assert conflict.json()["detail"] == "Idempotency-Key was already used for a different request"
    assert len(adapter.contexts) == 1
    history = asyncio.run(store.load_recent_messages(
        principal_id="user-a", conversation_id="conversation-1", limit=10
    ))
    assert len(history) == 2


def test_pending_idempotency_key_blocks_duplicate_provider_execution(tmp_path):
    service, _, adapter = build_persistent_service(
        tmp_path / "lila.sqlite3",
        adapter=RecordingModelAdapter(error=RuntimeError("ambiguous provider outcome")),
    )
    headers = {
        "Authorization": "Bearer user-a-token",
        "Idempotency-Key": str(uuid4()),
    }

    with make_client(service) as client:
        first = client.post("/v1/lila/turns", headers=headers, json=turn_payload())
        retry = client.post("/v1/lila/turns", headers=headers, json=turn_payload())

    assert first.status_code == 503
    assert retry.status_code == 409
    assert retry.headers["Retry-After"] == "2"
    assert len(adapter.contexts) == 1
    assert "ambiguous provider outcome" not in retry.text


def test_idempotency_keys_are_scoped_by_authenticated_owner(tmp_path):
    service, store, adapter = build_persistent_service(tmp_path / "lila.sqlite3")
    key = str(uuid4())

    with make_client(service) as client:
        owner_a = client.post(
            "/v1/lila/turns",
            headers={"Authorization": "Bearer user-a-token", "Idempotency-Key": key},
            json=turn_payload(),
        )
        owner_b = client.post(
            "/v1/lila/turns",
            headers={"Authorization": "Bearer user-b-token", "Idempotency-Key": key},
            json=turn_payload(),
        )

    assert owner_a.status_code == owner_b.status_code == 200
    assert len(adapter.contexts) == 2
    history_a = asyncio.run(store.load_recent_messages(
        principal_id="user-a", conversation_id="conversation-1", limit=10
    ))
    history_b = asyncio.run(store.load_recent_messages(
        principal_id="user-b", conversation_id="conversation-1", limit=10
    ))
    assert len(history_a) == len(history_b) == 2


def test_invalid_idempotency_header_is_rejected_before_model_execution():
    service, _, _, adapter = build_service()
    with make_client(service) as client:
        response = client.post(
            "/v1/lila/turns",
            headers={
                "Authorization": "Bearer user-a-token",
                "Idempotency-Key": "not-a-uuid",
            },
            json=turn_payload(),
        )
    assert response.status_code == 422
    assert adapter.contexts == []



def test_lila_history_requires_bearer_token(tmp_path):
    store = SqliteLilaStore(tmp_path / "lila.sqlite3", STORE_KEY)
    asyncio.run(store.append_turn(
        principal_id="user-a",
        conversation_id="conversation-1",
        user_message="private history",
        assistant_message="private answer",
    ))

    with make_client(history_reader=store) as client:
        response = client.get(
            "/v1/lila/conversations/conversation-1/messages"
        )

    assert response.status_code == 401


def test_lila_history_is_owner_scoped_and_paginates_without_gaps(tmp_path):
    store = SqliteLilaStore(tmp_path / "lila.sqlite3", STORE_KEY)
    for index in range(3):
        asyncio.run(store.append_turn(
            principal_id="user-a",
            conversation_id="shared-conversation",
            user_message=f"user-{index}",
            assistant_message=f"assistant-{index}",
            provider_id=f"provider-{index}",
            model_id=f"model-{index}",
            fallback_from_provider_id="primary-provider" if index == 2 else None,
            fallback_from_model_id="primary-model" if index == 2 else None,
            fallback_reason="primary_connection_unavailable" if index == 2 else None,
        ))
    asyncio.run(store.append_turn(
        principal_id="user-b",
        conversation_id="shared-conversation",
        user_message="owner-b-private",
        assistant_message="owner-b-answer",
    ))

    with make_client(history_reader=store) as client:
        headers_a = {"Authorization": "Bearer user-a-token"}
        headers_b = {"Authorization": "Bearer user-b-token"}
        first = client.get(
            "/v1/lila/conversations/shared-conversation/messages",
            headers=headers_a,
            params={"limit": 2},
        )
        second = client.get(
            "/v1/lila/conversations/shared-conversation/messages",
            headers=headers_a,
            params={"limit": 2, "before_sequence": first.json()["next_before_sequence"]},
        )
        third = client.get(
            "/v1/lila/conversations/shared-conversation/messages",
            headers=headers_a,
            params={"limit": 2, "before_sequence": second.json()["next_before_sequence"]},
        )
        other_owner = client.get(
            "/v1/lila/conversations/shared-conversation/messages",
            headers=headers_b,
            params={"limit": 20},
        )

    assert first.status_code == second.status_code == third.status_code == 200
    assert [item["content"] for item in first.json()["messages"]] == [
        "user-2", "assistant-2"
    ]
    assert first.json()["messages"][0]["provider_id"] is None
    assert first.json()["messages"][0]["model_id"] is None
    assert first.json()["messages"][1]["provider_id"] == "provider-2"
    assert first.json()["messages"][1]["model_id"] == "model-2"
    assert first.json()["messages"][1]["fallback_used"] is True
    assert first.json()["messages"][1]["fallback_from_provider_id"] == "primary-provider"
    assert first.json()["messages"][1]["fallback_from_model_id"] == "primary-model"
    assert first.json()["messages"][1]["fallback_reason"] == "primary_connection_unavailable"
    assert first.json()["messages"][0]["fallback_used"] is False
    assert first.json()["next_before_sequence"] == 5
    assert [item["content"] for item in second.json()["messages"]] == [
        "user-1", "assistant-1"
    ]
    assert second.json()["next_before_sequence"] == 3
    assert [item["content"] for item in third.json()["messages"]] == [
        "user-0", "assistant-0"
    ]
    assert third.json()["next_before_sequence"] is None
    assert [item["content"] for item in other_owner.json()["messages"]] == [
        "owner-b-private", "owner-b-answer"
    ]
    assert "owner-b-private" not in [
        item["content"]
        for page in (first.json(), second.json(), third.json())
        for item in page["messages"]
    ]
    assert all(
        item["sequence"] > 0 and item["created_at_epoch_millis"] >= 0
        for page in (first.json(), second.json(), third.json())
        for item in page["messages"]
    )


def test_lila_history_fails_closed_without_history_reader_and_bounds_page_size():
    with make_client() as client:
        unavailable = client.get(
            "/v1/lila/conversations/conversation-1/messages",
            headers={"Authorization": "Bearer user-a-token"},
        )
        oversized = client.get(
            "/v1/lila/conversations/conversation-1/messages",
            headers={"Authorization": "Bearer user-a-token"},
            params={"limit": 21},
        )

    assert unavailable.status_code == 503
    assert oversized.status_code == 422



def test_lila_provider_probe_requires_bearer_token_without_outbound_call():
    adapter = RecordingProviderAdapter()
    limiter = RecordingTurnLimiter()
    with make_client(turn_limiter=limiter, provider_adapter=adapter) as client:
        response = client.post("/v1/lila/provider/probe")

    assert response.status_code == 401
    assert adapter.calls == 0
    assert limiter.calls == []


def test_lila_provider_probe_returns_only_bounded_non_secret_model_metadata():
    adapter = RecordingProviderAdapter(models=("qwen-local", "llama-local"))
    limiter = RecordingTurnLimiter()
    with make_client(turn_limiter=limiter, provider_adapter=adapter) as client:
        response = client.post(
            "/v1/lila/provider/probe",
            headers={"Authorization": "Bearer user-a-token"},
        )

    assert response.status_code == 200
    assert response.json() == {
        "provider_id": "operator-configured",
        "configured_model_id": "configured-model",
        "models_endpoint_responded": True,
        "model_discovery_supported": True,
        "model_ids": ["qwen-local", "llama-local"],
        "chat_completion_verified": False,
    }
    assert limiter.calls == ["user-a"]
    assert adapter.calls == 1
    assert "base_url" not in response.text
    assert "api_key" not in response.text
    assert "provider-secret" not in response.text


def test_lila_provider_probe_reports_unsupported_models_route_without_claiming_chat_test():
    adapter = RecordingProviderAdapter(models=None)
    with make_client(provider_adapter=adapter) as client:
        response = client.post(
            "/v1/lila/provider/probe",
            headers={"Authorization": "Bearer user-a-token"},
        )

    assert response.status_code == 200
    assert response.json()["models_endpoint_responded"] is True
    assert response.json()["model_discovery_supported"] is False
    assert response.json()["model_ids"] == []
    assert response.json()["chat_completion_verified"] is False


def test_lila_provider_probe_fails_closed_when_no_provider_is_configured():
    with make_client() as client:
        response = client.post(
            "/v1/lila/provider/probe",
            headers={"Authorization": "Bearer user-a-token"},
        )

    assert response.status_code == 503
    assert response.json()["detail"] == "Lila model provider is not configured"


def test_lila_provider_probe_obeys_per_user_rate_limit_before_network_call():
    adapter = RecordingProviderAdapter()
    limiter = RecordingTurnLimiter(
        error=HTTPException(
            status_code=429,
            detail="Lila turn rate limit exceeded",
            headers={"Retry-After": "60"},
        )
    )
    with make_client(turn_limiter=limiter, provider_adapter=adapter) as client:
        response = client.post(
            "/v1/lila/provider/probe",
            headers={"Authorization": "Bearer user-a-token"},
        )

    assert response.status_code == 429
    assert response.headers["Retry-After"] == "60"
    assert limiter.calls == ["user-a"]
    assert adapter.calls == 0


@pytest.mark.parametrize(
    ("error", "expected_status", "expected_detail"),
    [
        (
            ModelProviderConfigurationError("authentication_rejected"),
            502,
            "Lila model provider rejected authentication; verify its credentials and access permissions.",
        ),
        (
            ModelProviderUnavailable("private-provider-detail"),
            503,
            "Lila model endpoint is temporarily unavailable",
        ),
        (
            ModelAdapterContractError("private-provider-detail"),
            502,
            "Lila model provider returned an invalid model catalog",
        ),
    ],
)
def test_lila_provider_probe_sanitizes_provider_failures(
    error, expected_status, expected_detail
):
    adapter = RecordingProviderAdapter(error=error)
    with make_client(provider_adapter=adapter) as client:
        response = client.post(
            "/v1/lila/provider/probe",
            headers={"Authorization": "Bearer user-a-token"},
        )

    assert response.status_code == expected_status
    assert response.json()["detail"] == expected_detail
    assert "private-provider-detail" not in response.text
