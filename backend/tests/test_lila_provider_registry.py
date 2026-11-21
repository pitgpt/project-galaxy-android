import asyncio
import json
import sqlite3

import httpx
import pytest
from fastapi import HTTPException
from fastapi.testclient import TestClient

from galaxy_backend.config import Settings
from galaxy_backend.lila_core.memory import MemorySnippet
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
    run(first.set_conversation_model(
        principal_id="private-owner-a",
        conversation_id="private-conversation-a",
        provider_id="cloud-main",
        model_id="model-b",
    ))

    second = SqliteLilaStore(path, KEY)
    assert run(second.get_conversation_model(
        principal_id="private-owner-a",
        conversation_id="private-conversation-a",
    )) == {"provider_id": "cloud-main", "model_id": "model-b"}
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
