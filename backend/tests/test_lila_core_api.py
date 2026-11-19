import pytest
from fastapi import HTTPException
from fastapi.testclient import TestClient

from galaxy_backend.config import Settings
from galaxy_backend.lila_core.context import ContextAssembler
from galaxy_backend.lila_core.contracts import CoreTurnResponse
from galaxy_backend.lila_core.memory import ConversationMessage, MemorySnippet
from galaxy_backend.lila_core.service import CoreTurnService
from galaxy_backend.main import create_app


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

    async def load_recent_messages(self, *, principal_id, conversation_id, limit):
        self.calls.append((principal_id, conversation_id, limit))
        return [
            ConversationMessage(
                role="assistant",
                content=f"history:{principal_id}",
            )
        ]

    async def append_turn(self, *, principal_id, conversation_id, user_message, assistant_message):
        self.appended_turns.append((principal_id, conversation_id, user_message, assistant_message))


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
            avatar_events=[
                {
                    "type": "avatar.emotion",
                    "payload": {"emotion": "happy", "intensity": 0.75},
                }
            ],
        )


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


def make_client(service=None, turn_limiter=None):
    app = create_app(
        settings=make_settings(),
        http_client=FakeHttpClient(),
        authenticator=FakeAuthenticator(),
        lila_core_service=service,
        lila_turn_rate_limiter=turn_limiter,
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
