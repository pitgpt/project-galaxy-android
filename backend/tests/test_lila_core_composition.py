import json
from contextlib import asynccontextmanager
from dataclasses import replace

import pytest
from fastapi import HTTPException
from fastapi.testclient import TestClient

from galaxy_backend.config import Settings
from galaxy_backend.main import create_app
from galaxy_backend.lila_core.openai_compatible import (
    ModelProviderConfigurationError,
    ModelProviderUnavailable,
)


KEY = bytes.fromhex("0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef")


class FakeAuthenticator:
    def authenticate(self, token: str) -> str:
        if token != "owner-token":
            raise HTTPException(status_code=401, detail="Invalid token")
        return "owner-1"


class NoopTurnLimiter:
    async def check(self, principal_id):
        assert principal_id == "owner-1"


class RaisingCoreService:
    def __init__(self, error):
        self.error = error

    async def handle(self, request, *, principal_id):
        assert principal_id == "owner-1"
        raise self.error


class FakeResponse:
    def __init__(self, payload):
        self._payload = payload

    async def __aenter__(self):
        return self

    async def __aexit__(self, exc_type, exc, traceback):
        return False

    def raise_for_status(self):
        return None

    async def aiter_bytes(self, chunk_size=65536):
        encoded = json.dumps(self._payload, ensure_ascii=False).encode("utf-8")
        for offset in range(0, len(encoded), chunk_size):
            yield encoded[offset : offset + chunk_size]


class ScriptedHttpClient:
    def __init__(self):
        self.calls = []

    @asynccontextmanager
    async def stream(self, method, url, *, json, headers, timeout):
        assert method == "POST"
        self.calls.append({
            "url": url,
            "json": json,
            "headers": dict(headers),
            "timeout": timeout,
        })
        yield FakeResponse({
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


def make_app(settings, http_client, lila_core_service=None):
    return create_app(
        settings=settings,
        http_client=http_client,
        authenticator=FakeAuthenticator(),
        lila_core_service=lila_core_service,
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
    assert "provider-secret" not in repr(settings)
    assert KEY.hex() not in repr(settings)

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



def test_app_startup_rejects_partial_manual_lila_settings(tmp_path):
    settings = replace(
        make_settings(tmp_path / "lila.sqlite3"),
        lila_database_path=None,
    )
    app = make_app(settings, ScriptedHttpClient())

    with pytest.raises(RuntimeError, match="Lila configuration is incomplete"):
        with TestClient(app):
            pass

@pytest.mark.parametrize(
    ("reason_code", "expected_detail"),
    [
        (
            "authentication_rejected",
            "Lila model provider rejected authentication; verify its credentials and access permissions.",
        ),
        (
            "endpoint_or_model_not_found",
            "Lila model endpoint or model was not found; verify the configured base URL and model ID.",
        ),
        (
            "redirect_refused",
            "Lila model endpoint redirected the request; configure its final HTTPS endpoint directly.",
        ),
        (
            "request_rejected",
            "Lila model provider rejected the request; verify API compatibility and model configuration.",
        ),
    ],
)
def test_provider_configuration_errors_return_safe_bad_gateway(
    tmp_path, reason_code, expected_detail
):
    settings = make_settings(tmp_path / "lila.sqlite3")
    service = RaisingCoreService(ModelProviderConfigurationError(reason_code))
    app = make_app(settings, ScriptedHttpClient(), lila_core_service=service)

    with TestClient(app) as client:
        response = send_turn(client, "Проверь подключение")

    assert response.status_code == 502
    assert response.json()["detail"] == expected_detail
    assert "provider-secret" not in response.text
    assert "private-provider-detail" not in response.text


def test_transient_provider_failure_returns_retryable_service_unavailable(tmp_path):
    settings = make_settings(tmp_path / "lila.sqlite3")
    service = RaisingCoreService(
        ModelProviderUnavailable("provider-secret must remain private")
    )
    app = make_app(settings, ScriptedHttpClient(), lila_core_service=service)

    with TestClient(app) as client:
        response = send_turn(client, "Продолжим?")

    assert response.status_code == 503
    assert response.json()["detail"] == "Lila model provider is temporarily unavailable"
    assert response.headers["Retry-After"] == "5"
    assert "provider-secret" not in response.text
