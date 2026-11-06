import httpx
import pytest
from fastapi.testclient import TestClient

from galaxy_backend.config import Settings
from galaxy_backend.main import create_app
from galaxy_backend.security import safety_identifier


class FakeAuthenticator:
    def authenticate(self, token: str) -> str:
        if token != "valid-test-token":
            from fastapi import HTTPException
            raise HTTPException(status_code=401, detail="Invalid or expired access token")
        return "user-123"


class FakeProvider:
    def __init__(self, status_code=200):
        self.status_code = status_code
        self.calls = []

    async def post(self, url, json, headers):
        self.calls.append((url, json, headers))
        request = httpx.Request("POST", url)
        if self.status_code != 200:
            return httpx.Response(self.status_code, request=request, json={"error": {"message": "sensitive provider detail"}})
        return httpx.Response(
            200,
            request=request,
            json={"value": "ek_test_secret", "expires_at": 1_800_000_000, "session": {"id": "sess_test"}},
        )


@pytest.fixture
def settings():
    return Settings(
        openai_api_key="sk-server-only",
        auth_jwks_url="https://issuer.example/.well-known/jwks.json",
        auth_issuer="https://issuer.example/",
        auth_audience="galaxy-android",
        safety_identifier_hmac_key="test-hmac-secret-long-enough",
        allowed_voices=frozenset({"alloy", "coral"}),
        allowed_character_ids=frozenset({"galaxy.default", "character.test"}),
        client_secret_ttl_seconds=600,
        per_user_requests_per_minute=5,
    )


def client_for(settings, provider):
    app = create_app(settings=settings, http_client=provider, authenticator=FakeAuthenticator())
    return TestClient(app)


def test_health_does_not_require_auth(settings):
    with client_for(settings, FakeProvider()) as client:
        response = client.get("/health")
    assert response.status_code == 200
    assert response.json() == {"status": "ok"}


def test_session_requires_bearer_token(settings):
    with client_for(settings, FakeProvider()) as client:
        response = client.post("/v1/realtime/sessions", json={"character_id": "galaxy.default", "voice_id": "alloy"})
    assert response.status_code == 401


def test_creates_server_configured_session_and_returns_only_required_fields(settings):
    provider = FakeProvider()
    with client_for(settings, provider) as client:
        response = client.post(
            "/v1/realtime/sessions",
            headers={"Authorization": "Bearer valid-test-token"},
            json={"character_id": "galaxy.default", "voice_id": "coral"},
        )

    assert response.status_code == 200
    assert response.json() == {
        "client_secret": "ek_test_secret",
        "expires_at_epoch_millis": 1_800_000_000_000,
        "session_id": "sess_test",
    }
    url, body, headers = provider.calls[0]
    assert url == "https://api.openai.com/v1/realtime/client_secrets"
    assert body["expires_after"]["seconds"] == 600
    assert body["session"]["type"] == "realtime"
    assert body["session"]["model"] == "gpt-realtime"
    assert body["session"]["audio"]["output"]["voice"] == "coral"
    assert "instructions" in body["session"]
    assert headers["Authorization"] == "Bearer sk-server-only"
    assert headers["OpenAI-Safety-Identifier"] == safety_identifier("user-123", settings.safety_identifier_hmac_key)
    assert "sk-server-only" not in response.text


def test_rejects_unapproved_voice_without_calling_provider(settings):
    provider = FakeProvider()
    with client_for(settings, provider) as client:
        response = client.post(
            "/v1/realtime/sessions",
            headers={"Authorization": "Bearer valid-test-token"},
            json={"character_id": "galaxy.default", "voice_id": "unapproved-voice"},
        )
    assert response.status_code == 422
    assert provider.calls == []


def test_provider_errors_are_sanitized(settings):
    provider = FakeProvider(status_code=500)
    with client_for(settings, provider) as client:
        response = client.post(
            "/v1/realtime/sessions",
            headers={"Authorization": "Bearer valid-test-token"},
            json={"character_id": "galaxy.default", "voice_id": "alloy"},
        )
    assert response.status_code == 502
    assert "sensitive provider detail" not in response.text


def test_safety_identifier_is_stable_and_does_not_expose_user_id(settings):
    first = safety_identifier("user-123", settings.safety_identifier_hmac_key)
    assert first == safety_identifier("user-123", settings.safety_identifier_hmac_key)
    assert first != safety_identifier("user-456", settings.safety_identifier_hmac_key)
    assert "user-123" not in first


def test_rejects_unapproved_character_without_calling_provider(settings):
    provider = FakeProvider()
    with client_for(settings, provider) as client:
        response = client.post(
            "/v1/realtime/sessions",
            headers={"Authorization": "Bearer valid-test-token"},
            json={"character_id": "admin.override", "voice_id": "alloy"},
        )
    assert response.status_code == 422
    assert provider.calls == []
