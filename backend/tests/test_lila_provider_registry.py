import asyncio
import json
import sqlite3

import httpx
import pytest
from fastapi import HTTPException
from fastapi.testclient import TestClient

from galaxy_backend.config import Settings
from galaxy_backend.lila_core.adapters import ModelProviderConnectionUnavailable
from galaxy_backend.lila_core.context import AssembledContext
from galaxy_backend.lila_core.contracts import CoreTurnResponse
from galaxy_backend.lila_core.memory import MemorySnippet
from galaxy_backend.lila_core.openai_compatible import ModelProviderUnavailable
from galaxy_backend.lila_core.provider_routing import (
    OwnerScopedModelAdapterResolver,
    SelectedFallbackProviderUnavailable,
)
from galaxy_backend.lila_core.sqlite_store import (
    LilaStoreIntegrityError,
    ProviderAlreadyExists,
    ProviderLimitExceeded,
    SqliteLilaStore,
)
from galaxy_backend.main import create_app


KEY = bytes.fromhex("3031323334353637383961626364656630313233343536373839616263646566")


def run(coro):
    return asyncio.run(coro)


def make_provider(provider_id="cloud-main", **overrides):
    value = {
        "provider_id": provider_id,
        "provider_type": "cloud",
        "base_url": "https://api.vendor.com/v1",
        "model_id": "model-a",
        "api_key": "provider-secret-value",
        "timeout_seconds": 30.0,
        "capabilities": ["chat"],
        "enabled": True,
    }
    value.update(overrides)
    return value


def test_encrypted_provider_catalog_is_owner_scoped_and_survives_restart(tmp_path):
    path = tmp_path / "lila.sqlite3"
    first = SqliteLilaStore(path, KEY)
    run(first.save_provider(
        principal_id="private-owner-a",
        provider=make_provider(),
        create_only=True,
    ))
    run(first.save_provider(
        principal_id="private-owner-b",
        provider=make_provider(
            base_url="https://other.vendor.net/v1",
            model_id="model-b",
            api_key="other-provider-secret",
        ),
        create_only=True,
    ))

    second = SqliteLilaStore(path, KEY)
    owner_a = run(second.list_providers(principal_id="private-owner-a"))
    owner_b = run(second.list_providers(principal_id="private-owner-b"))

    assert len(owner_a) == len(owner_b) == 1
    assert owner_a[0]["provider_id"] == "cloud-main"
    assert owner_a[0]["base_url"] == "https://api.vendor.com/v1"
    assert owner_a[0]["api_key"] == "provider-secret-value"
    assert owner_b[0]["base_url"] == "https://other.vendor.net/v1"
    assert owner_b[0]["api_key"] == "other-provider-secret"

    database_bytes = b"".join(
        candidate.read_bytes()
        for candidate in (
            path,
            path.with_name(path.name + "-wal"),
            path.with_name(path.name + "-shm"),
        )
        if candidate.exists()
    )
    for value in (
        b"private-owner-a",
        b"private-owner-b",
        b"cloud-main",
        b"api.vendor.com",
        b"other.vendor.net",
        b"model-a",
        b"provider-secret-value",
        b"other-provider-secret",
    ):
        assert value not in database_bytes


def test_provider_upsert_preserves_owner_scope_and_delete_isolated(tmp_path):
    store = SqliteLilaStore(tmp_path / "lila.sqlite3", KEY)
    run(store.save_provider(
        principal_id="owner-a", provider=make_provider(), create_only=True
    ))
    run(store.save_provider(
        principal_id="owner-b",
        provider=make_provider(api_key="owner-b-secret"),
        create_only=True,
    ))

    updated = make_provider(model_id="model-c", api_key="rotated-secret")
    assert run(store.save_provider(
        principal_id="owner-a", provider=updated, create_only=False
    )) is False
    assert run(store.get_provider(
        principal_id="owner-a", provider_id="cloud-main"
    ))["model_id"] == "model-c"
    assert run(store.get_provider(
        principal_id="owner-b", provider_id="cloud-main"
    ))["api_key"] == "owner-b-secret"

    assert run(store.delete_provider(
        principal_id="owner-a", provider_id="cloud-main"
    )) is True
    assert run(store.delete_provider(
        principal_id="owner-a", provider_id="cloud-main"
    )) is False
    assert run(store.get_provider(
        principal_id="owner-b", provider_id="cloud-main"
    )) is not None


def test_provider_create_only_and_per_owner_limit(tmp_path, monkeypatch):
    monkeypatch.setattr(SqliteLilaStore, "_MAX_PROVIDERS_PER_OWNER", 1)
    store = SqliteLilaStore(tmp_path / "lila.sqlite3", KEY)
    run(store.save_provider(
        principal_id="owner-a", provider=make_provider(), create_only=True
    ))
    with pytest.raises(ProviderAlreadyExists):
        run(store.save_provider(
            principal_id="owner-a", provider=make_provider(), create_only=True
        ))
    with pytest.raises(ProviderLimitExceeded):
        run(store.save_provider(
            principal_id="owner-a",
            provider=make_provider("second-provider"),
            create_only=True,
        ))
    run(store.save_provider(
        principal_id="owner-b",
        provider=make_provider("second-provider"),
        create_only=True,
    ))


def test_schema_v4_migration_preserves_history_and_adds_provider_catalog(tmp_path):
    path = tmp_path / "lila.sqlite3"
    original = SqliteLilaStore(path, KEY)
    run(original.append_turn(
        principal_id="owner-a",
        conversation_id="chat",
        user_message="history before provider registry",
        assistant_message="still available",
    ))
    run(original.save_memory(
        principal_id="owner-a",
        memory=MemorySnippet(memory_id="m1", content="memory survives"),
    ))

    database = sqlite3.connect(path)
    # Simulate an actual v4 database: neither v5 nor v6 catalog tables exist yet.
    database.execute("DROP TABLE IF EXISTS lila_conversation_models")
    database.execute("DROP TABLE IF EXISTS lila_providers")
    database.execute("PRAGMA user_version=4")
    database.commit()
    database.close()

    migrated = SqliteLilaStore(path, KEY)
    history = run(migrated.load_recent_messages(
        principal_id="owner-a", conversation_id="chat", limit=10
    ))
    memories = run(migrated.search_relevant_memories(
        principal_id="owner-a", query="survives", limit=5
    ))
    run(migrated.save_provider(
        principal_id="owner-a", provider=make_provider(), create_only=True
    ))

    assert [message.content for message in history] == [
        "history before provider registry", "still available"
    ]
    assert memories[0].content == "memory survives"
    database = sqlite3.connect(path)
    assert database.execute("PRAGMA user_version").fetchone()[0] == 6
    assert database.execute(
        "SELECT COUNT(*) FROM lila_providers"
    ).fetchone()[0] == 1
    database.close()


def test_provider_ciphertext_tampering_fails_closed(tmp_path):
    path = tmp_path / "lila.sqlite3"
    store = SqliteLilaStore(path, KEY)
    run(store.save_provider(
        principal_id="owner-a", provider=make_provider(), create_only=True
    ))
    database = sqlite3.connect(path)
    row = database.execute(
        "SELECT ciphertext FROM lila_providers LIMIT 1"
    ).fetchone()
    damaged = bytearray(row[0])
    damaged[-1] ^= 1
    database.execute(
        "UPDATE lila_providers SET ciphertext=?", (bytes(damaged),)
    )
    database.commit()
    database.close()

    with pytest.raises(LilaStoreIntegrityError):
        run(store.list_providers(principal_id="owner-a"))


class FakeAuthenticator:
    def authenticate(self, token):
        identities = {"owner-a-token": "owner-a", "owner-b-token": "owner-b"}
        if token not in identities:
            raise HTTPException(status_code=401, detail="Invalid token")
        return identities[token]


class FakeHttpClient:
    async def aclose(self):
        pass


class RecordingLimiter:
    def __init__(self, error=None):
        self.error = error
        self.calls = []

    async def check(self, principal_id):
        self.calls.append(principal_id)
        if self.error is not None:
            raise self.error


def make_settings():
    return Settings(
        openai_api_key=None,
        auth_jwks_url="https://issuer.example/.well-known/jwks.json",
        auth_issuer="https://issuer.example/",
        auth_audience="galaxy-android",
        safety_identifier_hmac_key="test-hmac-secret-long-enough",
        allowed_voices=frozenset({"alloy"}),
        allowed_character_ids=frozenset({"galaxy.default"}),
        environment="test",
    )


def make_client(tmp_path, limiter=None, provider_http_client_factory=None):
    store = SqliteLilaStore(tmp_path / "lila.sqlite3", KEY)
    app = create_app(
        settings=make_settings(),
        http_client=FakeHttpClient(),
        authenticator=FakeAuthenticator(),
        lila_turn_rate_limiter=limiter or RecordingLimiter(),
        lila_provider_registry=store,
        lila_provider_http_client_factory=provider_http_client_factory,
        lila_store=store,
    )
    return TestClient(app), store


def auth(owner="a"):
    return {"Authorization": f"Bearer owner-{owner}-token"}


def create_payload(**overrides):
    value = {
        "provider_id": "cloud-main",
        "provider_type": "cloud",
        "base_url": "https://api.vendor.com/v1",
        "model_id": "model-a",
        "api_key": "provider-secret-value",
        "timeout_seconds": 30,
        "capabilities": ["chat"],
        "enabled": True,
    }
    value.update(overrides)
    return value


def test_provider_api_create_list_and_never_returns_endpoint_or_secret(tmp_path):
    client, _ = make_client(tmp_path)
    with client:
        created = client.post(
            "/v1/lila/providers", headers=auth(), json=create_payload()
        )
        listed = client.get("/v1/lila/providers", headers=auth())

    assert created.status_code == 200
    assert created.json() == {
        "provider_id": "cloud-main",
        "provider_type": "cloud",
        "model_id": "model-a",
        "capabilities": ["chat"],
        "timeout_seconds": 30.0,
        "enabled": True,
        "has_api_key": True,
    }
    assert listed.status_code == 200
    assert listed.json()["providers"] == [created.json()]
    for response in (created, listed):
        assert "provider-secret-value" not in response.text
        assert "api.vendor.com" not in response.text
        assert "base_url" not in response.text


def test_provider_api_is_owner_scoped_and_duplicate_is_conflict(tmp_path):
    client, _ = make_client(tmp_path)
    with client:
        created = client.post(
            "/v1/lila/providers", headers=auth("a"), json=create_payload()
        )
        duplicate = client.post(
            "/v1/lila/providers", headers=auth("a"), json=create_payload()
        )
        owner_b_list = client.get("/v1/lila/providers", headers=auth("b"))
        owner_b_delete = client.delete(
            "/v1/lila/providers/cloud-main", headers=auth("b")
        )
        owner_a_list = client.get("/v1/lila/providers", headers=auth("a"))

    assert created.status_code == 200
    assert duplicate.status_code == 409
    assert owner_b_list.status_code == 200
    assert owner_b_list.json()["providers"] == []
    assert owner_b_delete.status_code == 404
    assert len(owner_a_list.json()["providers"]) == 1


def test_provider_api_update_preserves_or_clears_secret_explicitly(tmp_path):
    client, _ = make_client(tmp_path)
    with client:
        client.post("/v1/lila/providers", headers=auth(), json=create_payload())
        update = client.put(
            "/v1/lila/providers/cloud-main",
            headers=auth(),
            json={
                "provider_type": "cloud",
                "base_url": "https://api.vendor.com/v1",
                "model_id": "model-b",
                "timeout_seconds": 45,
                "capabilities": ["chat"],
                "enabled": False,
            },
        )
        stored_after_update = client.get("/v1/lila/providers", headers=auth())
        cleared = client.put(
            "/v1/lila/providers/cloud-main",
            headers=auth(),
            json={
                "provider_type": "cloud",
                "base_url": "https://api.vendor.com/v1",
                "model_id": "model-b",
                "clear_api_key": True,
                "timeout_seconds": 45,
                "capabilities": ["chat"],
                "enabled": False,
            },
        )
        stored_after_clear = client.get("/v1/lila/providers", headers=auth())

    assert update.status_code == 200
    assert stored_after_update.json()["providers"][0]["has_api_key"] is True
    assert cleared.status_code == 200
    assert stored_after_clear.json()["providers"][0]["has_api_key"] is False
    assert "provider-secret-value" not in update.text + cleared.text


def test_provider_api_rejects_unsafe_endpoint_classes(tmp_path):
    client, _ = make_client(tmp_path)
    invalid = [
        create_payload(base_url="http://api.vendor.com/v1"),
        create_payload(base_url="https://127.0.0.1/v1"),
        create_payload(base_url="https://metadata.google.internal/"),
        create_payload(base_url="https://api.example.com/v1"),
        create_payload(provider_type="local", base_url="http://127.0.0.1:6379/"),
        create_payload(provider_type="local", base_url="http://host.docker.internal:6379/v1"),
        create_payload(
            provider_type="local",
            base_url="http://192.168.1.20:1234/v1",
        ),
    ]
    with client:
        responses = [
            client.post("/v1/lila/providers", headers=auth(), json=payload)
            for payload in invalid
        ]
        local = client.post(
            "/v1/lila/providers",
            headers=auth(),
            json=create_payload(
                provider_id="local-lmstudio",
                provider_type="local",
                base_url="http://127.0.0.1:1234/v1",
                api_key=None,
            ),
        )
        host_gateway = client.post(
            "/v1/lila/providers",
            headers=auth(),
            json=create_payload(
                provider_id="local-docker-host",
                provider_type="local",
                base_url="http://host.docker.internal:1234/v1",
                api_key=None,
            ),
        )

    assert all(response.status_code == 422 for response in responses)
    assert local.status_code == 200
    assert local.json()["provider_type"] == "local"
    assert host_gateway.status_code == 200


def test_provider_api_requires_auth_and_obeys_per_user_rate_limit(tmp_path):
    limiter = RecordingLimiter(
        error=HTTPException(
            status_code=429,
            detail="rate limit",
            headers={"Retry-After": "60"},
        )
    )
    client, store = make_client(tmp_path, limiter)
    with client:
        unauthenticated = client.post(
            "/v1/lila/providers", json=create_payload()
        )
        limited = client.post(
            "/v1/lila/providers", headers=auth(), json=create_payload()
        )

    assert unauthenticated.status_code == 401
    assert limited.status_code == 429
    assert run(store.list_providers(principal_id="owner-a")) == ()


def test_provider_api_validation_never_echoes_secret(tmp_path):
    client, _ = make_client(tmp_path)
    secret = "secret-that-must-not-be-echoed"
    with client:
        response = client.post(
            "/v1/lila/providers",
            headers=auth(),
            json=create_payload(api_key=secret + "\ninvalid"),
        )
    assert response.status_code == 422
    assert secret not in response.text


def make_provider_http_client_factory(handler, calls):
    def factory(provider_type, timeout_seconds):
        calls.append((provider_type, timeout_seconds))
        return httpx.AsyncClient(
            transport=httpx.MockTransport(handler),
            timeout=timeout_seconds,
            follow_redirects=False,
            trust_env=False,
        )
    return factory


def test_owner_managed_provider_probe_uses_dns_pinned_factory_and_never_leaks_secret(tmp_path):
    outbound = []
    factory_calls = []

    async def handler(request):
        outbound.append(request)
        return httpx.Response(
            200,
            json={"data": [{"id": "model-a"}, {"id": "model-b"}]},
        )

    factory = make_provider_http_client_factory(handler, factory_calls)
    client, _ = make_client(tmp_path, provider_http_client_factory=factory)
    with client:
        created = client.post(
            "/v1/lila/providers",
            headers=auth(),
            json=create_payload(),
        )
        probed = client.post(
            "/v1/lila/providers/cloud-main/probe",
            headers=auth(),
        )

    assert created.status_code == 200
    assert probed.status_code == 200
    assert probed.json() == {
        "provider_id": "cloud-main",
        "configured_model_id": "model-a",
        "connection_reachable": True,
        "model_discovery_supported": True,
        "configured_model_available": True,
        "model_ids": ["model-a", "model-b"],
        "chat_completion_verified": False,
    }
    assert factory_calls == [("cloud", 30.0)]
    assert len(outbound) == 1
    assert outbound[0].method == "GET"
    assert outbound[0].url.path == "/v1/models"
    assert outbound[0].headers["authorization"] == "Bearer provider-secret-value"
    assert "provider-secret-value" not in probed.text
    assert "api.vendor.com" not in probed.text
    assert "/chat/completions" not in str(outbound[0].url)


def test_owner_managed_provider_probe_reports_unsupported_models_without_claiming_chat_test(tmp_path):
    factory_calls = []

    async def handler(request):
        return httpx.Response(404, json={"error": "not found"})

    client, _ = make_client(
        tmp_path,
        provider_http_client_factory=make_provider_http_client_factory(handler, factory_calls),
    )
    with client:
        client.post("/v1/lila/providers", headers=auth(), json=create_payload())
        response = client.post(
            "/v1/lila/providers/cloud-main/probe",
            headers=auth(),
        )

    assert response.status_code == 200
    assert response.json()["connection_reachable"] is True
    assert response.json()["model_discovery_supported"] is False
    assert response.json()["configured_model_available"] is None
    assert response.json()["chat_completion_verified"] is False
    assert factory_calls == [("cloud", 30.0)]


def test_owner_managed_provider_probe_is_owner_scoped_and_skips_disabled_records(tmp_path):
    factory_calls = []

    async def handler(request):
        return httpx.Response(200, json={"data": [{"id": "model-a"}]})

    client, _ = make_client(
        tmp_path,
        provider_http_client_factory=make_provider_http_client_factory(handler, factory_calls),
    )
    with client:
        created = client.post("/v1/lila/providers", headers=auth("a"), json=create_payload())
        other_owner = client.post(
            "/v1/lila/providers/cloud-main/probe",
            headers=auth("b"),
        )
        disabled = client.put(
            "/v1/lila/providers/cloud-main",
            headers=auth("a"),
            json={
                "provider_type": "cloud",
                "base_url": "https://api.vendor.com/v1",
                "model_id": "model-a",
                "enabled": False,
            },
        )
        disabled_probe = client.post(
            "/v1/lila/providers/cloud-main/probe",
            headers=auth("a"),
        )

    assert created.status_code == 200
    assert other_owner.status_code == 404
    assert disabled.status_code == 200
    assert disabled_probe.status_code == 409
    assert factory_calls == []


def test_owner_managed_provider_probe_requires_auth_and_rate_limit_before_outbound(tmp_path):
    factory_calls = []
    limiter = RecordingLimiter(
        error=HTTPException(
            status_code=429,
            detail="rate limit",
            headers={"Retry-After": "60"},
        )
    )

    async def handler(request):
        return httpx.Response(200, json={"data": [{"id": "model-a"}]})

    client, _ = make_client(
        tmp_path,
        limiter=limiter,
        provider_http_client_factory=make_provider_http_client_factory(handler, factory_calls),
    )
    with client:
        unauthenticated = client.post("/v1/lila/providers/cloud-main/probe")
        limited = client.post(
            "/v1/lila/providers/cloud-main/probe",
            headers=auth(),
        )

    assert unauthenticated.status_code == 401
    assert limited.status_code == 429
    assert factory_calls == []
    assert limiter.calls == ["owner-a"]


def test_owner_managed_provider_probe_sanitizes_auth_failure(tmp_path):
    factory_calls = []
    secret = "provider-secret-value"

    async def handler(request):
        return httpx.Response(401, text=f"invalid credential {secret}")

    client, _ = make_client(
        tmp_path,
        provider_http_client_factory=make_provider_http_client_factory(handler, factory_calls),
    )
    with client:
        client.post("/v1/lila/providers", headers=auth(), json=create_payload())
        response = client.post(
            "/v1/lila/providers/cloud-main/probe",
            headers=auth(),
        )

    assert response.status_code == 502
    assert response.json()["detail"] == (
        "Lila model provider rejected authentication; verify its credentials and access permissions."
    )
    assert secret not in response.text
    assert factory_calls == [("cloud", 30.0)]



def test_schema_v5_migration_preserves_encrypted_provider_catalog(tmp_path):
    path = tmp_path / "lila.sqlite3"
    store = SqliteLilaStore(path, KEY)
    run(store.save_provider(
        principal_id="owner-a",
        provider=make_provider(),
        create_only=True,
    ))

    database = sqlite3.connect(path)
    database.execute("DROP TABLE IF EXISTS lila_conversation_models")
    database.execute("PRAGMA user_version=5")
    database.commit()
    database.close()

    migrated = SqliteLilaStore(path, KEY)
    providers = run(migrated.list_providers(principal_id="owner-a"))
    assert len(providers) == 1
    assert providers[0]["provider_id"] == "cloud-main"

    database = sqlite3.connect(path)
    assert database.execute("PRAGMA user_version").fetchone()[0] == 6
    assert database.execute(
        "SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name='lila_conversation_models'"
    ).fetchone()[0] == 1
    database.close()


def test_conversation_model_selection_is_encrypted_owner_scoped_and_survives_restart(tmp_path):
    path = tmp_path / "lila.sqlite3"
    first = SqliteLilaStore(path, KEY)
    run(first.save_provider(
        principal_id="private-owner-a",
        provider=make_provider(),
        create_only=True,
    ))
    run(first.save_provider(
        principal_id="private-owner-b",
        provider=make_provider(
            base_url="https://other.vendor.net/v1",
            model_id="model-a",
            api_key="owner-b-secret",
        ),
        create_only=True,
    ))
    run(first.save_provider(
        principal_id="private-owner-a",
        provider=make_provider(
            provider_id="cloud-backup",
            base_url="https://backup.vendor.net/v1",
            model_id="backup-model",
            api_key="backup-provider-secret",
        ),
        create_only=True,
    ))
    run(first.set_conversation_model(
        principal_id="private-owner-a",
        conversation_id="private-conversation-a",
        provider_id="cloud-main",
        model_id="model-b",
        fallback_provider_id="cloud-backup",
        fallback_model_id="backup-model",
    ))

    second = SqliteLilaStore(path, KEY)
    assert run(second.get_conversation_model(
        principal_id="private-owner-a",
        conversation_id="private-conversation-a",
    )) == {
        "provider_id": "cloud-main",
        "model_id": "model-b",
        "fallback_provider_id": "cloud-backup",
        "fallback_model_id": "backup-model",
    }
    assert run(second.get_conversation_model(
        principal_id="private-owner-b",
        conversation_id="private-conversation-a",
    )) is None
    assert run(second.get_conversation_model(
        principal_id="private-owner-a",
        conversation_id="another-conversation",
    )) is None

    database_bytes = b"".join(
        candidate.read_bytes()
        for candidate in (
            path,
            path.with_name(path.name + "-wal"),
            path.with_name(path.name + "-shm"),
        )
        if candidate.exists()
    )
    for value in (
        b"private-owner-a",
        b"private-conversation-a",
        b"cloud-main",
        b"model-b",
        b"cloud-backup",
        b"backup-model",
        b"backup-provider-secret",
    ):
        assert value not in database_bytes


def test_deleting_selected_provider_does_not_silently_clear_conversation_selection(tmp_path):
    store = SqliteLilaStore(tmp_path / "lila.sqlite3", KEY)
    run(store.save_provider(
        principal_id="owner-a", provider=make_provider(), create_only=True
    ))
    run(store.set_conversation_model(
        principal_id="owner-a",
        conversation_id="conversation-1",
        provider_id="cloud-main",
        model_id="model-b",
    ))
    assert run(store.delete_provider(
        principal_id="owner-a", provider_id="cloud-main"
    )) is True
    assert run(store.get_conversation_model(
        principal_id="owner-a", conversation_id="conversation-1"
    )) == {"provider_id": "cloud-main", "model_id": "model-b"}


def test_conversation_model_api_validates_discovery_and_restores_selection(tmp_path):
    factory_calls = []

    async def handler(request):
        assert request.method == "GET"
        assert request.url.path == "/v1/models"
        return httpx.Response(200, json={"data": [{"id": "model-a"}, {"id": "model-b"}]})

    client, _ = make_client(
        tmp_path,
        provider_http_client_factory=make_provider_http_client_factory(handler, factory_calls),
    )
    with client:
        created = client.post(
            "/v1/lila/providers", headers=auth(), json=create_payload()
        )
        selected = client.put(
            "/v1/lila/conversations/conversation-1/model",
            headers=auth(),
            json={"provider_id": "cloud-main", "model_id": "model-b"},
        )
        restored = client.get(
            "/v1/lila/conversations/conversation-1/model",
            headers=auth(),
        )
        rejected = client.put(
            "/v1/lila/conversations/conversation-2/model",
            headers=auth(),
            json={"provider_id": "cloud-main", "model_id": "missing-model"},
        )

    assert created.status_code == 200
    assert selected.status_code == 200
    assert selected.json() == {
        "conversation_id": "conversation-1",
        "selected": True,
        "provider_id": "cloud-main",
        "model_id": "model-b",
    }
    assert restored.status_code == 200
    assert restored.json() == selected.json()
    assert rejected.status_code == 422
    assert factory_calls == [("cloud", 30.0), ("cloud", 30.0)]


def test_conversation_model_selection_is_owner_scoped(tmp_path):
    factory_calls = []

    async def handler(request):
        return httpx.Response(200, json={"data": [{"id": "model-a"}]})

    client, _ = make_client(
        tmp_path,
        provider_http_client_factory=make_provider_http_client_factory(handler, factory_calls),
    )
    with client:
        created = client.post(
            "/v1/lila/providers", headers=auth("a"), json=create_payload()
        )
        other_owner = client.put(
            "/v1/lila/conversations/conversation-1/model",
            headers=auth("b"),
            json={"provider_id": "cloud-main", "model_id": "model-a"},
        )

    assert created.status_code == 200
    assert other_owner.status_code == 404
    assert factory_calls == []


def test_turn_uses_selected_owner_provider_model_and_replays_without_second_generation(tmp_path):
    outbound = []
    factory_calls = []

    async def handler(request):
        outbound.append(request)
        if request.method == "GET":
            return httpx.Response(
                200,
                json={"data": [{"id": "model-a"}, {"id": "model-b"}]},
            )
        return httpx.Response(
            200,
            json={"choices": [{"message": {"content": "Ответ выбранной модели"}}]},
        )

    client, store = make_client(
        tmp_path,
        provider_http_client_factory=make_provider_http_client_factory(handler, factory_calls),
    )
    headers = {
        **auth(),
        "Idempotency-Key": "a9f2a3a8-5a8b-4c45-8f5e-7e9c2a0a8f11",
    }
    payload = {"conversation_id": "conversation-1", "message": "Привет, Лила"}
    with client:
        created = client.post("/v1/lila/providers", headers=auth(), json=create_payload())
        selected = client.put(
            "/v1/lila/conversations/conversation-1/model",
            headers=auth(),
            json={"provider_id": "cloud-main", "model_id": "model-b"},
        )
        first = client.post("/v1/lila/turns", headers=headers, json=payload)
        replay = client.post("/v1/lila/turns", headers=headers, json=payload)
        history = client.get(
            "/v1/lila/conversations/conversation-1/messages",
            headers=auth(),
        )

    assert created.status_code == selected.status_code == 200
    assert first.status_code == replay.status_code == history.status_code == 200
    assert first.json() == replay.json()
    assert first.json()["text"] == "Ответ выбранной модели"
    assert first.json()["provider_id"] == "cloud-main"
    assert first.json()["model_id"] == "model-b"
    assert [request.method for request in outbound] == ["GET", "POST"]
    assert outbound[1].url.path == "/v1/chat/completions"
    assert outbound[1].read()
    assert b'"model":"model-b"' in outbound[1].content
    assert outbound[1].headers["authorization"] == "Bearer provider-secret-value"
    assert "provider-secret-value" not in first.text
    assistant = history.json()["messages"][-1]
    assert assistant["provider_id"] == "cloud-main"
    assert assistant["model_id"] == "model-b"
    assert len(run(store.load_recent_messages(
        principal_id="owner-a",
        conversation_id="conversation-1",
        limit=10,
    ))) == 2


def test_pre_send_connection_failure_is_retry_safe_and_reuses_idempotency_key(tmp_path):
    outbound = []
    factory_calls = []
    fail_first_generation = True
    generation_count = 0

    async def handler(request):
        nonlocal fail_first_generation, generation_count
        outbound.append((request.method, request.url.path))
        if request.method == "GET":
            return httpx.Response(200, json={"data": [{"id": "model-a"}]})
        if fail_first_generation:
            fail_first_generation = False
            raise httpx.ConnectError(
                "provider-secret private-provider-detail",
                request=request,
            )
        generation_count += 1
        return httpx.Response(
            200,
            json={"choices": [{"message": {"content": "Ответ после восстановления"}}]},
        )

    client, store = make_client(
        tmp_path,
        provider_http_client_factory=make_provider_http_client_factory(handler, factory_calls),
    )
    headers = {
        **auth(),
        "Idempotency-Key": "c5d64c80-4a7b-4f64-91e9-52f8dff4c412",
    }
    payload = {"conversation_id": "conversation-retry", "message": "Привет"}
    with client:
        created = client.post("/v1/lila/providers", headers=auth(), json=create_payload())
        selected = client.put(
            "/v1/lila/conversations/conversation-retry/model",
            headers=auth(),
            json={"provider_id": "cloud-main", "model_id": "model-a"},
        )
        first = client.post("/v1/lila/turns", headers=headers, json=payload)
        retry = client.post("/v1/lila/turns", headers=headers, json=payload)

    assert created.status_code == selected.status_code == 200
    assert first.status_code == 503
    assert first.headers["X-Lila-Retry-Safe"] == "true"
    assert "provider-secret" not in first.text
    assert "private-provider-detail" not in first.text
    assert retry.status_code == 200
    assert retry.json()["text"] == "Ответ после восстановления"
    assert retry.json()["provider_id"] == "cloud-main"
    assert retry.json()["model_id"] == "model-a"
    assert generation_count == 1
    assert [method for method, _ in outbound].count("POST") == 2
    history = run(store.load_recent_messages(
        principal_id="owner-a",
        conversation_id="conversation-retry",
        limit=10,
    ))
    assert [message.content for message in history] == ["Привет", "Ответ после восстановления"]


def test_ambiguous_turn_failure_is_marked_and_never_generates_again(tmp_path):
    outbound = []
    factory_calls = []

    async def handler(request):
        outbound.append((request.method, request.url.path))
        if request.method == "GET":
            return httpx.Response(200, json={"data": [{"id": "model-a"}]})
        raise httpx.ReadTimeout(
            "provider-secret private-provider-detail",
            request=request,
        )

    client, _ = make_client(
        tmp_path,
        provider_http_client_factory=make_provider_http_client_factory(handler, factory_calls),
    )
    headers = {
        **auth(),
        "Idempotency-Key": "7b1d8b3d-ff8f-4cc8-9d9b-0a3d2f8b4a61",
    }
    payload = {
        "conversation_id": "conversation-ambiguous",
        "message": "Сообщение с неопределённым результатом",
    }
    with client:
        created = client.post(
            "/v1/lila/providers",
            headers=auth(),
            json=create_payload(),
        )
        selected = client.put(
            "/v1/lila/conversations/conversation-ambiguous/model",
            headers=auth(),
            json={"provider_id": "cloud-main", "model_id": "model-a"},
        )
        failed = client.post("/v1/lila/turns", headers=headers, json=payload)
        replay = client.post("/v1/lila/turns", headers=headers, json=payload)

    assert created.status_code == selected.status_code == 200
    assert failed.status_code == 503
    assert failed.headers["X-Lila-Outcome-Unknown"] == "true"
    assert "X-Lila-Retry-Safe" not in failed.headers
    assert "provider-secret" not in failed.text
    assert "private-provider-detail" not in failed.text
    assert replay.status_code == 409
    assert "still processing" in replay.json()["detail"]
    assert outbound == [
        ("GET", "/v1/models"),
        ("POST", "/v1/chat/completions"),
    ]


def test_turn_requires_explicit_selection_when_owner_has_registered_providers(tmp_path):
    outbound = []
    factory_calls = []

    async def handler(request):
        outbound.append(request)
        return httpx.Response(200, json={"data": [{"id": "model-a"}]})

    client, _ = make_client(
        tmp_path,
        provider_http_client_factory=make_provider_http_client_factory(handler, factory_calls),
    )
    with client:
        created = client.post("/v1/lila/providers", headers=auth(), json=create_payload())
        response = client.post(
            "/v1/lila/turns",
            headers=auth(),
            json={"conversation_id": "conversation-1", "message": "Привет"},
        )

    assert created.status_code == 200
    assert response.status_code == 409
    assert response.json()["detail"].startswith("Select a model provider")
    assert outbound == []
    assert factory_calls == []


def test_turn_does_not_silently_fallback_when_selected_provider_is_deleted(tmp_path):
    outbound = []
    factory_calls = []

    async def handler(request):
        outbound.append(request)
        if request.method == "GET":
            return httpx.Response(200, json={"data": [{"id": "model-a"}]})
        return httpx.Response(
            200,
            json={"choices": [{"message": {"content": "unexpected fallback"}}]},
        )

    client, _ = make_client(
        tmp_path,
        provider_http_client_factory=make_provider_http_client_factory(handler, factory_calls),
    )
    with client:
        client.post("/v1/lila/providers", headers=auth(), json=create_payload())
        selected = client.put(
            "/v1/lila/conversations/conversation-1/model",
            headers=auth(),
            json={"provider_id": "cloud-main", "model_id": "model-a"},
        )
        deleted = client.delete("/v1/lila/providers/cloud-main", headers=auth())
        response = client.post(
            "/v1/lila/turns",
            headers=auth(),
            json={"conversation_id": "conversation-1", "message": "Продолжим"},
        )

    assert selected.status_code == deleted.status_code == 200
    assert response.status_code == 409
    assert "unavailable or disabled" in response.json()["detail"]
    assert [request.method for request in outbound] == ["GET"]


def test_provider_chat_test_runs_one_completion_without_persisting_history(tmp_path):
    outbound = []
    factory_calls = []

    async def handler(request):
        outbound.append(request)
        assert request.method == "POST"
        assert request.url.path == "/v1/chat/completions"
        payload = json.loads(request.content)
        assert payload["model"] == "model-a"
        assert payload["stream"] is False
        assert payload["messages"][-1] == {
            "role": "user",
            "content": "Reply with exactly: LILA_OK",
        }
        return httpx.Response(
            200,
            json={"choices": [{"message": {"content": "LILA_OK"}}]},
        )

    client, store = make_client(
        tmp_path,
        provider_http_client_factory=make_provider_http_client_factory(handler, factory_calls),
    )
    with client:
        created = client.post(
            "/v1/lila/providers",
            headers=auth(),
            json=create_payload(),
        )
        tested = client.post(
            "/v1/lila/providers/cloud-main/test-chat",
            headers=auth(),
        )

    assert created.status_code == 200
    assert tested.status_code == 200
    assert tested.json() == {
        "provider_id": "cloud-main",
        "model_id": "model-a",
        "chat_completion_verified": True,
        "response_characters": 7,
    }
    assert "provider-secret-value" not in tested.text
    assert "api.vendor.com" not in tested.text
    assert len(outbound) == 1
    assert len(factory_calls) == 1
    history = run(store.load_recent_messages(
        principal_id="owner-a",
        conversation_id="lila-provider-chat-test",
        limit=10,
    ))
    assert not history


def test_provider_chat_test_refuses_disabled_provider_without_egress(tmp_path):
    outbound = []
    factory_calls = []

    async def handler(request):
        outbound.append(request)
        return httpx.Response(
            200,
            json={"choices": [{"message": {"content": "unexpected"}}]},
        )

    client, _ = make_client(
        tmp_path,
        provider_http_client_factory=make_provider_http_client_factory(handler, factory_calls),
    )
    with client:
        created = client.post(
            "/v1/lila/providers",
            headers=auth(),
            json=create_payload(enabled=False),
        )
        tested = client.post(
            "/v1/lila/providers/cloud-main/test-chat",
            headers=auth(),
        )

    assert created.status_code == 200
    assert tested.status_code == 409
    assert outbound == []
    assert factory_calls == []


def test_provider_chat_test_marks_only_pre_send_failure_retry_safe(tmp_path):
    async def handler(request):
        raise httpx.ConnectError(
            "provider-secret private diagnostic",
            request=request,
        )

    client, _ = make_client(
        tmp_path,
        provider_http_client_factory=make_provider_http_client_factory(handler, []),
    )
    with client:
        client.post(
            "/v1/lila/providers",
            headers=auth(),
            json=create_payload(),
        )
        tested = client.post(
            "/v1/lila/providers/cloud-main/test-chat",
            headers=auth(),
        )

    assert tested.status_code == 503
    assert tested.headers["X-Lila-Retry-Safe"] == "true"
    assert "provider-secret" not in tested.text
    assert "private diagnostic" not in tested.text


def test_provider_chat_test_does_not_mark_ambiguous_failure_retry_safe(tmp_path):
    async def handler(request):
        raise httpx.ReadTimeout(
            "provider-secret private diagnostic",
            request=request,
        )

    client, _ = make_client(
        tmp_path,
        provider_http_client_factory=make_provider_http_client_factory(handler, []),
    )
    with client:
        client.post(
            "/v1/lila/providers",
            headers=auth(),
            json=create_payload(),
        )
        tested = client.post(
            "/v1/lila/providers/cloud-main/test-chat",
            headers=auth(),
        )

    assert tested.status_code == 503
    assert "X-Lila-Retry-Safe" not in tested.headers
    assert tested.headers["X-Lila-Outcome-Unknown"] == "true"
    assert "provider-secret" not in tested.text
    assert "private diagnostic" not in tested.text


def test_invalid_generated_response_marks_outcome_unknown_and_keeps_reservation(tmp_path):
    outbound = []

    async def handler(request):
        outbound.append((request.method, request.url.path))
        if request.method == "GET":
            return httpx.Response(200, json={"data": [{"id": "model-a"}]})
        return httpx.Response(
            200,
            json={"error": {"message": "provider-secret malformed-response-detail"}},
        )

    client, _ = make_client(
        tmp_path,
        provider_http_client_factory=make_provider_http_client_factory(handler, []),
    )
    headers = {
        **auth(),
        "Idempotency-Key": "d7a4a2d2-2df2-4c3a-8f61-83e5c02a2c2a",
    }
    payload = {
        "conversation_id": "conversation-invalid-response",
        "message": "Проверь некорректный ответ",
    }
    with client:
        created = client.post(
            "/v1/lila/providers",
            headers=auth(),
            json=create_payload(),
        )
        selected = client.put(
            "/v1/lila/conversations/conversation-invalid-response/model",
            headers=auth(),
            json={"provider_id": "cloud-main", "model_id": "model-a"},
        )
        failed = client.post("/v1/lila/turns", headers=headers, json=payload)
        replay = client.post("/v1/lila/turns", headers=headers, json=payload)

    assert created.status_code == selected.status_code == 200
    assert failed.status_code == 502
    assert failed.headers["X-Lila-Outcome-Unknown"] == "true"
    assert "X-Lila-Retry-Safe" not in failed.headers
    assert "provider-secret" not in failed.text
    assert "malformed-response-detail" not in failed.text
    assert replay.status_code == 409
    assert outbound == [
        ("GET", "/v1/models"),
        ("POST", "/v1/chat/completions"),
    ]


def test_local_provider_runs_authenticated_api_to_core_history_over_real_pinned_socket(tmp_path):
    """Exercise the complete owner-managed local path over a real TCP socket.

    The test uses only loopback and one of the production allowlisted runtime
    ports. It does not depend on an installed model runtime or external network.
    """
    import threading
    from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

    from galaxy_backend.lila_core.provider_egress import create_provider_http_client
    from galaxy_backend.lila_core.provider_registry import DEFAULT_LOCAL_PROVIDER_PORTS

    class ProviderHandler(BaseHTTPRequestHandler):
        protocol_version = "HTTP/1.1"
        observed = []

        def log_message(self, format, *args):
            return

        def _send_json(self, payload):
            encoded = json.dumps(payload, ensure_ascii=False).encode("utf-8")
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(encoded)))
            self.send_header("Connection", "close")
            self.end_headers()
            self.wfile.write(encoded)

        def do_GET(self):
            self.__class__.observed.append(
                ("GET", self.path, self.headers.get("Authorization"))
            )
            if self.path != "/v1/models":
                self.send_error(404)
                return
            self._send_json({
                "object": "list",
                "data": [{"id": "local-integration-model"}],
            })

        def do_POST(self):
            length = int(self.headers.get("Content-Length", "0"))
            payload = json.loads(self.rfile.read(length))
            self.__class__.observed.append(
                ("POST", self.path, self.headers.get("Authorization"), payload)
            )
            if self.path != "/v1/chat/completions":
                self.send_error(404)
                return
            last_message = payload["messages"][-1]["content"]
            content = (
                "LILA_OK"
                if last_message == "Reply with exactly: LILA_OK"
                else "Ответ через настоящий loopback HTTP"
            )
            self._send_json({
                "choices": [{"message": {"content": content}}],
            })

    server = None
    for candidate_port in sorted(DEFAULT_LOCAL_PROVIDER_PORTS):
        try:
            server = ThreadingHTTPServer(("127.0.0.1", candidate_port), ProviderHandler)
            break
        except OSError:
            continue
    if server is None:
        pytest.skip("No production-allowlisted local model port is available")

    server.daemon_threads = True
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    port = server.server_address[1]

    def real_provider_client_factory(provider_type, timeout_seconds):
        from galaxy_backend.lila_core.provider_egress import create_provider_http_client
        return create_provider_http_client(provider_type, timeout_seconds)

    try:
        client, store = make_client(
            tmp_path,
            provider_http_client_factory=real_provider_client_factory,
        )
        headers = {
            **auth(),
            "Idempotency-Key": "4a7cbcc8-2e3e-49ab-9d92-37d9d53b0f11",
        }
        with client:
            created = client.post(
                "/v1/lila/providers",
                headers=auth(),
                json=create_payload(
                    provider_id="local-integration",
                    provider_type="local",
                    base_url=f"http://127.0.0.1:{port}/v1",
                    model_id="local-integration-model",
                    api_key="integration-only-secret",
                ),
            )
            probe = client.post(
                "/v1/lila/providers/local-integration/probe",
                headers=auth(),
            )
            chat_test = client.post(
                "/v1/lila/providers/local-integration/test-chat",
                headers=auth(),
            )
            selected = client.put(
                "/v1/lila/conversations/live-local/model",
                headers=auth(),
                json={
                    "provider_id": "local-integration",
                    "model_id": "local-integration-model",
                },
            )
            turn_payload = {
                "conversation_id": "live-local",
                "message": "Ответь через локальный сервер",
            }
            first = client.post(
                "/v1/lila/turns",
                headers=headers,
                json=turn_payload,
            )
            replay = client.post(
                "/v1/lila/turns",
                headers=headers,
                json=turn_payload,
            )
            history = client.get(
                "/v1/lila/conversations/live-local/messages",
                headers=auth(),
            )

        assert created.status_code == 200
        assert "integration-only-secret" not in created.text
        assert probe.status_code == 200
        assert probe.json()["configured_model_available"] is True
        assert probe.json()["chat_completion_verified"] is False
        assert chat_test.status_code == 200
        assert chat_test.json()["chat_completion_verified"] is True
        assert chat_test.json()["response_characters"] == 7
        assert "integration-only-secret" not in chat_test.text
        assert selected.status_code == 200
        assert first.status_code == replay.status_code == history.status_code == 200
        assert first.json() == replay.json()
        assert first.json()["text"] == "Ответ через настоящий loopback HTTP"
        assert first.json()["provider_id"] == "local-integration"
        assert first.json()["model_id"] == "local-integration-model"
        assert history.json()["messages"][-1]["provider_id"] == "local-integration"
        assert history.json()["messages"][-1]["model_id"] == "local-integration-model"

        observed = ProviderHandler.observed
        assert [item[0:2] for item in observed] == [
            ("GET", "/v1/models"),
            ("POST", "/v1/chat/completions"),
            ("GET", "/v1/models"),
            ("POST", "/v1/chat/completions"),
        ]
        assert all(
            item[2] == "Bearer integration-only-secret"
            for item in observed
        )
        assert not run(store.load_recent_messages(
            principal_id="owner-a",
            conversation_id="lila-provider-chat-test",
            limit=10,
        ))
        persisted = run(store.load_recent_messages(
            principal_id="owner-a",
            conversation_id="live-local",
            limit=10,
        ))
        assert [message.content for message in persisted] == [
            "Ответь через локальный сервер",
            "Ответ через настоящий loopback HTTP",
        ]
    finally:
        server.shutdown()
        server.server_close()
        thread.join(timeout=2)



def test_turn_result_status_is_owner_and_conversation_scoped_and_replays_completed_response(tmp_path):
    client, store = make_client(tmp_path)
    key = "7b1d8b3d-ff8f-4cc8-9d9b-0a3d2f8b4a61"
    values = {
        "principal_id": "owner-a",
        "conversation_id": "conversation-status",
        "idempotency_key": key,
        "user_message": "private status request",
    }
    with client:
        assert run(store.reserve_idempotent_turn(**values)) is None
        pending = client.get(
            f"/v1/lila/conversations/conversation-status/turns/{key}",
            headers=auth("a"),
        )
        wrong_owner = client.get(
            f"/v1/lila/conversations/conversation-status/turns/{key}",
            headers=auth("b"),
        )
        wrong_conversation = client.get(
            f"/v1/lila/conversations/another-conversation/turns/{key}",
            headers=auth("a"),
        )
        response = CoreTurnResponse(
            conversation_id="conversation-status",
            text="Ответ, сохранённый до потери HTTP-соединения",
            provider_id="cloud-primary",
            model_id="model-a",
        )
        run(store.complete_idempotent_turn(
            **values,
            assistant_message=response.text,
            response=response,
        ))
        completed = client.get(
            f"/v1/lila/conversations/conversation-status/turns/{key}",
            headers=auth("a"),
        )

    assert pending.status_code == 200
    assert pending.json() == {
        "conversation_id": "conversation-status",
        "status": "pending",
        "response": None,
    }
    assert wrong_owner.status_code == 404
    assert wrong_conversation.status_code == 404
    assert completed.status_code == 200
    assert completed.json() == {
        "conversation_id": "conversation-status",
        "status": "completed",
        "response": {
            "conversation_id": "conversation-status",
            "text": "Ответ, сохранённый до потери HTTP-соединения",
            "emotion": "neutral",
            "avatar_events": [],
            "provider_id": "cloud-primary",
            "model_id": "model-a",
        },
    }
    assert "private status request" not in completed.text
    assert "provider-secret" not in completed.text


def test_turn_result_status_survives_store_recreation(tmp_path):
    path = tmp_path / "lila.sqlite3"
    first = SqliteLilaStore(path, KEY)
    values = {
        "principal_id": "owner-a",
        "conversation_id": "conversation-restart",
        "idempotency_key": "9f2f6d65-8a2e-4d5f-b1b1-8bde6d98f001",
        "user_message": "survive backend restart",
    }
    assert run(first.reserve_idempotent_turn(**values)) is None
    response = CoreTurnResponse(
        conversation_id="conversation-restart",
        text="durable response",
        provider_id="local-ollama",
        model_id="qwen-local",
    )
    run(first.complete_idempotent_turn(
        **values,
        assistant_message=response.text,
        response=response,
    ))

    reopened = SqliteLilaStore(path, KEY)
    status, recovered = run(reopened.get_idempotent_turn_result(
        principal_id="owner-a",
        conversation_id="conversation-restart",
        idempotency_key=values["idempotency_key"],
    ))

    assert status == "completed"
    assert recovered == response



def test_native_ollama_probe_test_chat_and_turn_use_native_api_and_replay_once(tmp_path):
    outbound = []
    generation_calls = 0

    async def handler(request):
        nonlocal generation_calls
        outbound.append((request.method, request.url.path, request.headers.get("Authorization")))
        if request.method == "GET" and request.url.path == "/api/tags":
            return httpx.Response(
                200,
                json={"models": [{"name": "qwen3:8b"}, {"model": "llama3.2:latest"}]},
            )
        if request.method == "POST" and request.url.path == "/api/chat":
            generation_calls += 1
            body = json.loads(request.content)
            return httpx.Response(
                200,
                json={
                    "model": body["model"],
                    "message": {"role": "assistant", "content": "Ответ от Ollama"},
                    "done": True,
                },
            )
        return httpx.Response(404, json={"error": "not found"})

    factory_calls = []
    client, store = make_client(
        tmp_path,
        provider_http_client_factory=make_provider_http_client_factory(handler, factory_calls),
    )
    provider_payload = create_payload(
        provider_id="ollama-local",
        provider_type="local",
        base_url="http://127.0.0.1:11434",
        model_id="qwen3:8b",
        api_key=None,
    )
    idempotency_key = "d2f3a4b5-c6d7-4890-9abc-def012345678"
    turn_headers = {
        **auth(),
        "Idempotency-Key": idempotency_key,
    }
    turn_payload = {
        "conversation_id": "conversation-ollama",
        "message": "Привет, Лила",
    }

    with client:
        created = client.post("/v1/lila/providers", headers=auth(), json=provider_payload)
        probed = client.post("/v1/lila/providers/ollama-local/probe", headers=auth())
        selected = client.put(
            "/v1/lila/conversations/conversation-ollama/model",
            headers=auth(),
            json={"provider_id": "ollama-local", "model_id": "qwen3:8b"},
        )
        chat_test = client.post(
            "/v1/lila/providers/ollama-local/test-chat",
            headers=auth(),
        )
        first_turn = client.post(
            "/v1/lila/turns",
            headers=turn_headers,
            json=turn_payload,
        )
        replay = client.post(
            "/v1/lila/turns",
            headers=turn_headers,
            json=turn_payload,
        )
        history = client.get(
            "/v1/lila/conversations/conversation-ollama/messages",
            headers=auth(),
        )

    assert created.status_code == 200
    assert probed.status_code == 200
    assert probed.json()["model_discovery_supported"] is True
    assert probed.json()["configured_model_available"] is True
    assert probed.json()["model_ids"] == ["qwen3:8b", "llama3.2:latest"]
    assert selected.status_code == 200
    assert selected.json()["model_id"] == "qwen3:8b"
    assert chat_test.status_code == 200
    assert chat_test.json()["chat_completion_verified"] is True
    assert first_turn.status_code == 200
    assert first_turn.json()["text"] == "Ответ от Ollama"
    assert first_turn.json()["provider_id"] == "ollama-local"
    assert first_turn.json()["model_id"] == "qwen3:8b"
    assert replay.status_code == 200
    assert replay.json() == first_turn.json()
    assert history.status_code == 200
    assert [item["content"] for item in history.json()["messages"]] == [
        "Привет, Лила",
        "Ответ от Ollama",
    ]
    assert generation_calls == 2  # explicit test-chat plus one committed turn
    assert [path for method, path, _ in outbound] == [
        "/api/tags",
        "/api/tags",
        "/api/chat",
        "/api/chat",
    ]
    assert all(authorization is None for _, _, authorization in outbound)
    assert len(factory_calls) == 4
    assert "provider-secret-value" not in (
        created.text + probed.text + selected.text + chat_test.text + first_turn.text
    )
    assert run(store.get_conversation_model(
        principal_id="owner-a",
        conversation_id="conversation-ollama",
    )) == {"provider_id": "ollama-local", "model_id": "qwen3:8b"}


def test_authenticated_memory_api_upsert_search_and_delete_are_owner_scoped(tmp_path):
    client, _ = make_client(tmp_path)
    memory_id = "preferred-language"
    content = "The user prefers Russian language and concise replies."

    with client:
        unauthenticated = client.put(
            f"/v1/lila/memories/{memory_id}",
            json={"content": content},
        )
        saved = client.put(
            f"/v1/lila/memories/{memory_id}",
            headers=auth("a"),
            json={"content": content},
        )
        owner_a = client.get(
            "/v1/lila/memories/search",
            headers=auth("a"),
            params={"query": "Russian language"},
        )
        owner_b = client.get(
            "/v1/lila/memories/search",
            headers=auth("b"),
            params={"query": "Russian language"},
        )
        wrong_owner_delete = client.delete(
            f"/v1/lila/memories/{memory_id}",
            headers=auth("b"),
        )
        still_present = client.get(
            "/v1/lila/memories/search",
            headers=auth("a"),
            params={"query": "Russian language"},
        )
        deleted = client.delete(
            f"/v1/lila/memories/{memory_id}",
            headers=auth("a"),
        )
        after_delete = client.get(
            "/v1/lila/memories/search",
            headers=auth("a"),
            params={"query": "Russian language"},
        )

    assert unauthenticated.status_code == 401
    assert saved.status_code == 200
    assert saved.json() == {"memory_id": memory_id, "saved": True}
    assert owner_a.status_code == 200
    assert owner_a.json()["memories"] == [
        {"memory_id": memory_id, "content": content}
    ]
    assert owner_b.status_code == 200
    assert owner_b.json()["memories"] == []
    assert wrong_owner_delete.status_code == 200
    assert wrong_owner_delete.json() == {"memory_id": memory_id, "deleted": False}
    assert still_present.json()["memories"] == owner_a.json()["memories"]
    assert deleted.json() == {"memory_id": memory_id, "deleted": True}
    assert after_delete.json()["memories"] == []


def test_saved_owner_memory_is_assembled_into_the_real_core_turn(tmp_path):
    outbound = []

    async def handler(request):
        if request.method == "GET" and request.url.path == "/v1/models":
            return httpx.Response(200, json={"data": [{"id": "model-a"}]})
        body = json.loads(request.content)
        outbound.append(body)
        return httpx.Response(
            200,
            json={"choices": [{"message": {"content": "Ответ с учётом памяти"}}]},
        )

    factory_calls = []
    client, _ = make_client(
        tmp_path,
        provider_http_client_factory=make_provider_http_client_factory(handler, factory_calls),
    )
    content = "The user prefers Russian language and concise replies."

    with client:
        saved = client.put(
            "/v1/lila/memories/preferred-language",
            headers=auth("a"),
            json={"content": content},
        )
        provider = client.post(
            "/v1/lila/providers",
            headers=auth("a"),
            json=create_payload(),
        )
        selected = client.put(
            "/v1/lila/conversations/memory-chat/model",
            headers=auth("a"),
            json={"provider_id": "cloud-main", "model_id": "model-a"},
        )
        turn = client.post(
            "/v1/lila/turns",
            headers=auth("a"),
            json={
                "conversation_id": "memory-chat",
                "message": "Which language should you use?",
            },
        )

    assert saved.status_code == provider.status_code == selected.status_code == 200
    assert turn.status_code == 200
    assert turn.json()["text"] == "Ответ с учётом памяти"
    assert turn.json()["provider_id"] == "cloud-main"
    assert turn.json()["model_id"] == "model-a"
    assert len(outbound) == 1
    memory_message = next(
        message
        for message in outbound[0]["messages"]
        if "Untrusted memory excerpts" in message["content"]
    )
    assert content in memory_message["content"]
    assert "provider-secret-value" not in turn.text
    assert len(factory_calls) == 2


def test_memory_api_rejects_blank_content_and_obeys_per_user_rate_limit(tmp_path):
    limited = RecordingLimiter(
        error=HTTPException(
            status_code=429,
            detail="rate limit",
            headers={"Retry-After": "60"},
        )
    )
    client, _ = make_client(tmp_path, limited)

    with client:
        invalid = client.put(
            "/v1/lila/memories/blank",
            headers=auth("a"),
            json={"content": "   "},
        )
        rate_limited = client.put(
            "/v1/lila/memories/valid",
            headers=auth("a"),
            json={"content": "A valid memory"},
        )

    assert invalid.status_code == 422
    assert rate_limited.status_code == 429



def test_conversation_model_api_persists_and_restores_explicit_fallback(tmp_path):
    factory_calls = []

    async def handler(request):
        assert request.method == "GET"
        assert request.url.path == "/v1/models"
        if request.url.host == "backup.vendor.net":
            return httpx.Response(200, json={"data": [{"id": "backup-model"}]})
        return httpx.Response(200, json={"data": [{"id": "model-a"}]})

    client, _ = make_client(
        tmp_path,
        provider_http_client_factory=make_provider_http_client_factory(handler, factory_calls),
    )
    primary_payload = make_provider()
    backup_payload = make_provider(
        provider_id="cloud-backup",
        base_url="https://backup.vendor.net/v1",
        model_id="backup-model",
        api_key="backup-secret",
    )
    with client:
        primary = client.post("/v1/lila/providers", headers=auth(), json=primary_payload)
        backup = client.post("/v1/lila/providers", headers=auth(), json=backup_payload)
        selected = client.put(
            "/v1/lila/conversations/conversation-fallback/model",
            headers=auth(),
            json={
                "provider_id": "cloud-main",
                "model_id": "model-a",
                "fallback_provider_id": "cloud-backup",
                "fallback_model_id": "backup-model",
            },
        )
        restored = client.get(
            "/v1/lila/conversations/conversation-fallback/model",
            headers=auth(),
        )
        invalid_same_provider = client.put(
            "/v1/lila/conversations/conversation-invalid/model",
            headers=auth(),
            json={
                "provider_id": "cloud-main",
                "model_id": "model-a",
                "fallback_provider_id": "cloud-main",
                "fallback_model_id": "model-a",
            },
        )

    assert primary.status_code == 200
    assert backup.status_code == 200
    assert selected.status_code == 200
    expected = {
        "conversation_id": "conversation-fallback",
        "selected": True,
        "provider_id": "cloud-main",
        "model_id": "model-a",
        "fallback_provider_id": "cloud-backup",
        "fallback_model_id": "backup-model",
    }
    assert selected.json() == expected
    assert restored.json() == expected
    assert invalid_same_provider.status_code == 422
    assert factory_calls == [("cloud", 30.0), ("cloud", 30.0)]


def _fallback_provider(provider_id, host, model_id):
    return {
        "provider_id": provider_id,
        "provider_type": "cloud",
        "base_url": f"https://{host}/v1",
        "model_id": model_id,
        "api_key": None,
        "timeout_seconds": 5.0,
        "capabilities": ["chat"],
        "enabled": True,
    }


class _FallbackRegistry:
    def __init__(self, records):
        self.records = records

    async def get_provider(self, *, principal_id, provider_id):
        return self.records.get(provider_id)

    async def list_providers(self, *, principal_id):
        return list(self.records.values())


class _FallbackSelectionStore:
    def __init__(self, selection):
        self.selection = selection

    async def get_conversation_model(self, *, principal_id, conversation_id):
        return self.selection


def _fallback_resolver(records, selection, handler):
    return OwnerScopedModelAdapterResolver(
        registry=_FallbackRegistry(records),
        selection_store=_FallbackSelectionStore(selection),
        default_adapter=None,
        system_prompt="You are Lila.",
        http_client_factory=lambda provider_type, timeout: httpx.AsyncClient(
            transport=httpx.MockTransport(handler),
            timeout=timeout,
        ),
    )


def _fallback_context():
    return AssembledContext(conversation_id="conversation-1", messages=())


def test_explicit_fallback_runs_only_after_pre_send_connection_failure():
    calls = []

    async def handler(request):
        calls.append(request.url.host)
        if request.url.host == "primary.vendor.net":
            raise httpx.ConnectError("private transport detail", request=request)
        return httpx.Response(
            200,
            json={"choices": [{"message": {"content": "Ответ резервной модели"}}]},
        )

    resolver = _fallback_resolver(
        {
            "primary": _fallback_provider("primary", "primary.vendor.net", "primary-model"),
            "backup": _fallback_provider("backup", "backup.vendor.net", "backup-model"),
        },
        {
            "provider_id": "primary",
            "model_id": "primary-model",
            "fallback_provider_id": "backup",
            "fallback_model_id": "backup-model",
        },
        handler,
    )

    async def execute():
        adapter = await resolver("owner-a", "conversation-1")
        return await adapter.generate(_fallback_context())

    result = run(execute())
    assert result.text == "Ответ резервной модели"
    assert result.provider_id == "backup"
    assert result.model_id == "backup-model"
    assert result.fallback_used is True
    assert result.fallback_from_provider_id == "primary"
    assert result.fallback_from_model_id == "primary-model"
    assert result.fallback_reason == "primary_connection_unavailable"
    assert calls == ["primary.vendor.net", "backup.vendor.net"]


def test_ambiguous_primary_http_failure_never_triggers_fallback():
    calls = []

    async def handler(request):
        calls.append(request.url.host)
        return httpx.Response(503, json={"error": "private provider detail"})

    resolver = _fallback_resolver(
        {
            "primary": _fallback_provider("primary", "primary.vendor.net", "primary-model"),
            "backup": _fallback_provider("backup", "backup.vendor.net", "backup-model"),
        },
        {
            "provider_id": "primary",
            "model_id": "primary-model",
            "fallback_provider_id": "backup",
            "fallback_model_id": "backup-model",
        },
        handler,
    )

    async def execute():
        adapter = await resolver("owner-a", "conversation-1")
        return await adapter.generate(_fallback_context())

    with pytest.raises(ModelProviderUnavailable):
        run(execute())
    assert calls == ["primary.vendor.net"]


def test_missing_primary_uses_only_the_explicit_owner_fallback():
    calls = []

    async def handler(request):
        calls.append(request.url.host)
        return httpx.Response(
            200,
            json={"choices": [{"message": {"content": "Ответ резервной модели"}}]},
        )

    resolver = _fallback_resolver(
        {"backup": _fallback_provider("backup", "backup.vendor.net", "backup-model")},
        {
            "provider_id": "primary",
            "model_id": "primary-model",
            "fallback_provider_id": "backup",
            "fallback_model_id": "backup-model",
        },
        handler,
    )

    async def execute():
        adapter = await resolver("owner-a", "conversation-1")
        return await adapter.generate(_fallback_context())

    result = run(execute())
    assert result.fallback_used is True
    assert result.fallback_reason == "primary_provider_unavailable"
    assert result.provider_id == "backup"
    assert calls == ["backup.vendor.net"]


def test_primary_connection_failure_without_configured_fallback_does_not_choose_another_provider():
    calls = []

    async def handler(request):
        calls.append(request.url.host)
        raise httpx.ConnectError("offline", request=request)

    resolver = _fallback_resolver(
        {
            "primary": _fallback_provider("primary", "primary.vendor.net", "primary-model"),
            "other": _fallback_provider("other", "other.vendor.net", "other-model"),
        },
        {"provider_id": "primary", "model_id": "primary-model"},
        handler,
    )

    async def execute():
        adapter = await resolver("owner-a", "conversation-1")
        return await adapter.generate(_fallback_context())

    with pytest.raises(ModelProviderConnectionUnavailable):
        run(execute())
    assert calls == ["primary.vendor.net"]


def test_primary_connection_failure_with_missing_configured_fallback_is_explicit():
    calls = []

    async def handler(request):
        calls.append(request.url.host)
        raise httpx.ConnectError("offline", request=request)

    resolver = _fallback_resolver(
        {"primary": _fallback_provider("primary", "primary.vendor.net", "primary-model")},
        {
            "provider_id": "primary",
            "model_id": "primary-model",
            "fallback_provider_id": "backup",
            "fallback_model_id": "backup-model",
        },
        handler,
    )

    async def execute():
        adapter = await resolver("owner-a", "conversation-1")
        return await adapter.generate(_fallback_context())

    with pytest.raises(SelectedFallbackProviderUnavailable):
        run(execute())
    assert calls == ["primary.vendor.net"]
