import json

import pytest
from fastapi import HTTPException
from fastapi.testclient import TestClient

from galaxy_backend.config import Settings
from galaxy_backend.main import create_app


KEY = bytes.fromhex("0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef")


class FakeAuthenticator:
    def authenticate(self, token: str) -> str:
        if token != "owner-token":
            raise HTTPException(status_code=401, detail="Invalid token")
        return "owner-1"


class NoopTurnLimiter:
    async def check(self, principal_id):
        assert principal_id == "owner-1"


class FakeResponse:
    def __init__(self, payload):
        self._payload = payload

    def raise_for_status(self):
        return None

    def json(self):
        return self._payload


class ScriptedHttpClient:
    def __init__(self):
        self.calls = []

    async def post(self, url, *, json, headers, timeout):
        self.calls.append({
            "url": url,
            "json": json,
            "headers": dict(headers),
            "timeout": timeout,
        })
        return FakeResponse({
            "choices": [{"message": {"content": f"Ответ {len(self.calls)}"}}]
        })

    async def aclose(self):
        return None


def make_settings(database_path):
    return Settings(
        openai_api_key=None,
        auth_jwks_url="https://issuer.example/.well-known/jwks.json",
        auth_issuer="https://issuer.example/",
        auth_audience="galaxy-android",
        safety_identifier_hmac_key=None,
        allowed_voices=frozenset({"alloy"}),
        allowed_character_ids=frozenset({"galaxy.default"}),
        lila_provider_id="local-test",
        lila_provider_base_url="http://127.0.0.1:1234/v1",
        lila_provider_model_id="qwen-local",
        lila_provider_api_key="provider-secret",
        lila_provider_timeout_seconds=9,
        lila_database_path=str(database_path),
        lila_storage_encryption_key=KEY,
    )


def make_app(settings, http_client):
    return create_app(
        settings=settings,
        http_client=http_client,
        authenticator=FakeAuthenticator(),
        lila_turn_rate_limiter=NoopTurnLimiter(),
    )


def send_turn(client, message):
    return client.post(
        "/v1/lila/turns",
        headers={"Authorization": "Bearer owner-token"},
        json={"conversation_id": "shared-chat", "message": message},
    )


def test_composition_root_persists_history_across_app_restart(tmp_path):
    database_path = tmp_path / "lila.sqlite3"
    settings = make_settings(database_path)
    provider = ScriptedHttpClient()

    first_app = make_app(settings, provider)
    with TestClient(first_app) as client:
        first = send_turn(client, "Привет, Лила")
    assert first.status_code == 200
    assert first.json()["text"] == "Ответ 1"
    assert first.json()["provider_id"] == "local-test"
    assert first.json()["model_id"] == "qwen-local"

    second_app = make_app(settings, provider)
    with TestClient(second_app) as client:
        second = send_turn(client, "Продолжим наш разговор?")
    assert second.status_code == 200
    assert second.json()["text"] == "Ответ 2"

    second_messages = provider.calls[1]["json"]["messages"]
    assert second_messages[0]["role"] == "system"
    assert second_messages[-3:] == [
        {"role": "user", "content": "Привет, Лила"},
        {"role": "assistant", "content": "Ответ 1"},
        {"role": "user", "content": "Продолжим наш разговор?"},
    ]
    assert provider.calls[0]["url"] == "http://127.0.0.1:1234/v1/chat/completions"
    assert provider.calls[0]["headers"]["Authorization"] == "Bearer provider-secret"
    assert "provider-secret" not in first.text
    assert "provider-secret" not in second.text
    assert database_path.exists()


def test_local_lila_does_not_require_realtime_cloud_credentials(tmp_path):
    settings = make_settings(tmp_path / "lila.sqlite3")
    provider = ScriptedHttpClient()
    app = make_app(settings, provider)

    with TestClient(app) as client:
        response = client.post(
            "/v1/realtime/sessions",
            headers={"Authorization": "Bearer owner-token"},
            json={"character_id": "galaxy.default", "voice_id": "alloy"},
        )

    assert response.status_code == 503
    assert response.json()["detail"] == "Realtime provider is not configured"
    assert provider.calls == []


def test_settings_can_load_local_lila_without_openai_voice_key(monkeypatch):
    for name in (
        "OPENAI_API_KEY",
        "SAFETY_IDENTIFIER_HMAC_KEY",
        "LILA_PROVIDER_BASE_URL",
        "LILA_MODEL_ID",
        "LILA_PROVIDER_API_KEY",
        "LILA_DATABASE_PATH",
        "LILA_STORAGE_ENCRYPTION_KEY",
    ):
        monkeypatch.delenv(name, raising=False)
    monkeypatch.setenv("AUTH_JWKS_URL", "https://issuer.example/.well-known/jwks.json")
    monkeypatch.setenv("AUTH_ISSUER", "https://issuer.example/")
    monkeypatch.setenv("AUTH_AUDIENCE", "galaxy-android")
    monkeypatch.setenv("GALAXY_ENVIRONMENT", "development")

    settings = Settings.from_env()

    assert settings.openai_api_key is None
    assert settings.safety_identifier_hmac_key is None
    assert not settings.lila_is_configured


def test_settings_reject_partial_lila_configuration(monkeypatch):
    monkeypatch.setenv("AUTH_JWKS_URL", "https://issuer.example/.well-known/jwks.json")
    monkeypatch.setenv("AUTH_ISSUER", "https://issuer.example/")
    monkeypatch.setenv("AUTH_AUDIENCE", "galaxy-android")
    monkeypatch.setenv("GALAXY_ENVIRONMENT", "development")
    monkeypatch.setenv("LILA_PROVIDER_BASE_URL", "http://127.0.0.1:1234/v1")
    monkeypatch.delenv("LILA_MODEL_ID", raising=False)
    monkeypatch.delenv("LILA_DATABASE_PATH", raising=False)
    monkeypatch.delenv("LILA_STORAGE_ENCRYPTION_KEY", raising=False)
    monkeypatch.delenv("LILA_PROVIDER_API_KEY", raising=False)

    with pytest.raises(RuntimeError, match="Lila configuration is incomplete"):
        Settings.from_env()


def test_settings_reject_production_without_lila_storage_and_provider(monkeypatch):
    monkeypatch.setenv("AUTH_JWKS_URL", "https://issuer.example/.well-known/jwks.json")
    monkeypatch.setenv("AUTH_ISSUER", "https://issuer.example/")
    monkeypatch.setenv("AUTH_AUDIENCE", "galaxy-android")
    monkeypatch.setenv("GALAXY_ENVIRONMENT", "production")
    monkeypatch.setenv("REDIS_URL", "redis://localhost:6379/0")
    for name in (
        "LILA_PROVIDER_BASE_URL",
        "LILA_MODEL_ID",
        "LILA_DATABASE_PATH",
        "LILA_STORAGE_ENCRYPTION_KEY",
        "LILA_PROVIDER_API_KEY",
    ):
        monkeypatch.delenv(name, raising=False)

    with pytest.raises(RuntimeError, match="Production requires a configured Lila model provider"):
        Settings.from_env()
