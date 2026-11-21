import asyncio
import sqlite3

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
    # Simulate an actual v4 database: the v5 provider table does not exist yet.
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
    assert database.execute("PRAGMA user_version").fetchone()[0] == 5
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


def make_client(tmp_path, limiter=None):
    store = SqliteLilaStore(tmp_path / "lila.sqlite3", KEY)
    app = create_app(
        settings=make_settings(),
        http_client=FakeHttpClient(),
        authenticator=FakeAuthenticator(),
        lila_turn_rate_limiter=limiter or RecordingLimiter(),
        lila_provider_registry=store,
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

    assert all(response.status_code == 422 for response in responses)
    assert local.status_code == 200
    assert local.json()["provider_type"] == "local"


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
