import httpx
import pytest
from fastapi import HTTPException
from fastapi.testclient import TestClient

from galaxy_backend.config import Settings
from galaxy_backend.main import create_app
from galaxy_backend.security import RedisRateLimiter, safety_identifier
from galaxy_backend.session_registry import InMemorySessionRegistry


class FakeAuthenticator:
    def authenticate(self, token: str) -> str:
        identities = {
            "valid-test-token": "user-123",
            "another-user-token": "user-456",
        }
        if token not in identities:
            raise HTTPException(status_code=401, detail="Invalid or expired access token")
        return identities[token]


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


class FakeRedis:
    def __init__(self):
        self.counts = {}

    async def eval(self, script, number_of_keys, *args):
        assert number_of_keys == 2
        assert "INCR" in script and "PEXPIRE" in script
        minute_key, daily_key, minute_ttl, daily_ttl = args
        self.counts[minute_key] = self.counts.get(minute_key, 0) + 1
        self.counts[daily_key] = self.counts.get(daily_key, 0) + 1
        return [self.counts[minute_key], self.counts[daily_key]]


class UnavailableRedis:
    async def eval(self, *args):
        raise OSError("redis offline")


@pytest.fixture
def settings():
    return Settings(
        openai_api_key="sk-server-only",
        auth_jwks_url="https://issuer.example/.well-known/jwks.json",
        auth_issuer="https://issuer.example/",
        auth_audience="galaxy-android",
        safety_identifier_hmac_key="test-hmac-secret-long-enough",
        allowed_voices=frozenset({"alloy", "coral", "shimmer"}),
        allowed_character_ids=frozenset({"galaxy.default", "character.test"}),
        client_secret_ttl_seconds=600,
        per_user_requests_per_minute=5,
        per_user_sessions_per_day=100,
        max_active_sessions=2,
        session_lease_seconds=90,
    )


def client_for(settings, provider, rate_limiter=None, session_registry=None):
    app = create_app(
        settings=settings,
        http_client=provider,
        authenticator=FakeAuthenticator(),
        rate_limiter=rate_limiter,
        session_registry=session_registry,
    )
    return TestClient(app)


def test_health_does_not_require_auth(settings):
    with client_for(settings, FakeProvider()) as client:
        response = client.get("/health")
    assert response.status_code == 200
    assert response.json() == {"status": "ok"}


def test_readiness_is_available_without_auth(settings):
    with client_for(settings, FakeProvider()) as client:
        response = client.get("/ready")
    assert response.status_code == 200
    assert response.json() == {"status": "ready"}


def test_request_logs_exclude_credentials_and_response_secrets(settings, caplog):
    provider = FakeProvider()
    with client_for(settings, provider) as client:
        response = client.post(
            "/v1/realtime/sessions",
            headers={"Authorization": "Bearer valid-test-token"},
            json={"character_id": "galaxy.default", "voice_id": "alloy"},
        )
    assert response.status_code == 200
    assert response.headers.get("X-Request-ID")
    messages = " ".join(record.getMessage() for record in caplog.records)
    assert "valid-test-token" not in messages
    assert "ek_test_secret" not in messages
    assert "sk-server-only" not in messages


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
    assert response.json()["client_secret"] == "ek_test_secret"
    assert response.json()["expires_at_epoch_millis"] == 1_800_000_000_000
    assert len(response.json()["session_id"]) == 32
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



@pytest.mark.parametrize(
    ("profile_voice_id", "expected_provider_voice"),
    [
        ("voice.feminine_warm", "coral"),
        ("voice.feminine_calm", "shimmer"),
        ("voice.neutral", "alloy"),
    ],
)
def test_legacy_profile_voice_ids_map_to_supported_provider_voices(
    settings, profile_voice_id, expected_provider_voice
):
    provider = FakeProvider()
    with client_for(settings, provider) as client:
        response = client.post(
            "/v1/realtime/sessions",
            headers={"Authorization": "Bearer valid-test-token"},
            json={"character_id": "galaxy.default", "voice_id": profile_voice_id},
        )

    assert response.status_code == 200
    assert provider.calls[0][1]["session"]["audio"]["output"]["voice"] == expected_provider_voice


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


def test_provider_errors_are_sanitized_and_release_reservation(settings):
    provider = FakeProvider(status_code=500)
    registry = InMemorySessionRegistry(max_active=1, lease_seconds=90)
    with client_for(settings, provider, session_registry=registry) as client:
        response = client.post(
            "/v1/realtime/sessions",
            headers={"Authorization": "Bearer valid-test-token"},
            json={"character_id": "galaxy.default", "voice_id": "alloy"},
        )
        retry = client.post(
            "/v1/realtime/sessions",
            headers={"Authorization": "Bearer valid-test-token"},
            json={"character_id": "galaxy.default", "voice_id": "alloy"},
        )
    assert response.status_code == 502
    assert retry.status_code == 502
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


@pytest.mark.asyncio
async def test_redis_limiter_allows_minute_limit_then_rejects_excess():
    limiter = RedisRateLimiter(FakeRedis(), limit=2, daily_limit=10)
    await limiter.check("user-123")
    await limiter.check("user-123")
    with pytest.raises(HTTPException) as error:
        await limiter.check("user-123")
    assert error.value.status_code == 429
    assert error.value.headers["Retry-After"] == "60"


@pytest.mark.asyncio
async def test_redis_limiter_enforces_daily_credential_quota():
    limiter = RedisRateLimiter(FakeRedis(), limit=10, daily_limit=2)
    await limiter.check("user-123")
    await limiter.check("user-123")
    with pytest.raises(HTTPException) as error:
        await limiter.check("user-123")
    assert error.value.status_code == 429
    assert error.value.detail == "Daily realtime session credential limit exceeded"
    assert error.value.headers["Retry-After"] == "86400"


@pytest.mark.asyncio
async def test_redis_limiter_does_not_store_raw_user_id():
    redis = FakeRedis()
    limiter = RedisRateLimiter(redis, limit=5)
    await limiter.check("sensitive-user-id")
    key = next(iter(redis.counts))
    assert "sensitive-user-id" not in key
    assert key.startswith("galaxy:realtime:rate:") or key.startswith("galaxy:realtime:daily:")


@pytest.mark.asyncio
async def test_redis_failure_fails_closed():
    limiter = RedisRateLimiter(UnavailableRedis(), limit=5)
    with pytest.raises(HTTPException) as error:
        await limiter.check("user-123")
    assert error.value.status_code == 503
    assert error.value.headers["Retry-After"] == "5"



def test_active_session_limit_and_heartbeat_and_close(settings):
    provider = FakeProvider()
    registry = InMemorySessionRegistry(max_active=1, lease_seconds=90)
    headers = {"Authorization": "Bearer valid-test-token"}
    payload = {"character_id": "galaxy.default", "voice_id": "alloy"}
    with client_for(settings, provider, session_registry=registry) as client:
        first = client.post("/v1/realtime/sessions", headers=headers, json=payload)
        first_id = first.json()["session_id"]
        blocked = client.post("/v1/realtime/sessions", headers=headers, json=payload)
        heartbeat = client.post(f"/v1/realtime/sessions/{first_id}/heartbeat", headers=headers)
        closed = client.delete(f"/v1/realtime/sessions/{first_id}", headers=headers)
        second = client.post("/v1/realtime/sessions", headers=headers, json=payload)
    assert first.status_code == 200
    assert blocked.status_code == 429
    assert len(provider.calls) == 2
    assert heartbeat.json()["status"] == "active"
    assert closed.json()["status"] == "closed"
    assert second.status_code == 200


def test_session_id_is_scoped_to_authenticated_user(settings):
    provider = FakeProvider()
    registry = InMemorySessionRegistry(max_active=2, lease_seconds=90)
    app = create_app(settings=settings, http_client=provider, authenticator=FakeAuthenticator(), session_registry=registry)
    with TestClient(app) as client:
        created = client.post(
            "/v1/realtime/sessions",
            headers={"Authorization": "Bearer valid-test-token"},
            json={"character_id": "galaxy.default", "voice_id": "alloy"},
        )
        foreign = client.post(
            f"/v1/realtime/sessions/{created.json()['session_id']}/heartbeat",
            headers={"Authorization": "Bearer another-user-token"},
        )
    assert foreign.status_code == 404

class FakeSessionRedis:
    def __init__(self):
        self.sets = {}
        self.calls = []

    async def eval(self, script, number_of_keys, key, *args):
        assert number_of_keys == 1
        self.calls.append((script, key, args))
        members = self.sets.setdefault(key, {})
        if "ZREMRANGEBYSCORE" in script:
            now, expires, maximum, session_id, ttl = args
            assert "ZCARD" in script and "ZADD" in script
            for sid, score in list(members.items()):
                if score <= float(now):
                    members.pop(sid, None)
            if len(members) >= int(maximum):
                return 0
            members[session_id] = float(expires)
            return 1
        if "ZSCORE" in script:
            session_id, now, expires, ttl = args
            score = members.get(session_id)
            if score is None:
                return 0
            if score <= float(now):
                members.pop(session_id, None)
                return 0
            members[session_id] = float(expires)
            return 1
        if "ZREM" in script:
            return int(members.pop(args[0], None) is not None)
        raise AssertionError("Unexpected Redis Lua script")


@pytest.mark.asyncio
async def test_redis_session_registry_reserves_atomically_and_scopes_users():
    from galaxy_backend.session_registry import RedisSessionRegistry
    redis = FakeSessionRedis()
    registry = RedisSessionRegistry(redis, max_active=1, lease_seconds=90)
    assert await registry.reserve("user-123", "session-a") is True
    assert await registry.reserve("user-123", "session-b") is False
    assert await registry.reserve("user-456", "session-b") is True
    script, key, _ = redis.calls[0]
    assert "ZREMRANGEBYSCORE" in script and "ZCARD" in script and "ZADD" in script
    assert "user-123" not in key
    assert key.startswith("galaxy:realtime:sessions:")
    assert len(redis.calls) == 3


@pytest.mark.asyncio
async def test_redis_session_registry_heartbeat_and_expired_lease():
    from galaxy_backend.session_registry import RedisSessionRegistry
    redis = FakeSessionRedis()
    registry = RedisSessionRegistry(redis, max_active=2, lease_seconds=90)
    assert await registry.reserve("user-123", "session-a") is True
    assert await registry.heartbeat("user-123", "session-a") is True
    assert await registry.heartbeat("user-123", "missing") is False
    key = registry._key("user-123")
    redis.sets[key]["expired"] = 0
    assert await registry.heartbeat("user-123", "expired") is False
    assert "expired" not in redis.sets[key]


@pytest.mark.asyncio
async def test_redis_session_registry_close_is_owner_scoped_and_idempotent():
    from galaxy_backend.session_registry import RedisSessionRegistry
    redis = FakeSessionRedis()
    registry = RedisSessionRegistry(redis, max_active=2, lease_seconds=90)
    assert await registry.reserve("user-123", "session-a") is True
    await registry.close("user-456", "session-a")
    assert await registry.heartbeat("user-123", "session-a") is True
    await registry.close("user-123", "session-a")
    await registry.close("user-123", "session-a")
    assert await registry.heartbeat("user-123", "session-a") is False


@pytest.mark.asyncio
async def test_in_memory_session_registry_concurrent_reservations_respect_limit():
    import asyncio
    registry = InMemorySessionRegistry(max_active=2, lease_seconds=90)
    results = await asyncio.gather(*(registry.reserve("user-123", f"session-{i}") for i in range(20)))
    assert sum(results) == 2
