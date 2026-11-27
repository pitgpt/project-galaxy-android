import json

from fastapi import HTTPException
from fastapi.testclient import TestClient

from galaxy_backend.config import Settings
from galaxy_backend.main import MAX_REALTIME_SESSION_RESPONSE_BYTES, create_app


class FakeAuthenticator:
    def authenticate(self, token: str) -> str:
        if token != "valid-token":
            raise HTTPException(status_code=401, detail="Invalid access token")
        return "owner-1"


class FakeSessionRegistry:
    def __init__(self):
        self.reserved = []
        self.closed = []

    async def reserve(self, principal_id: str, session_id: str) -> bool:
        self.reserved.append((principal_id, session_id))
        return True

    async def close(self, principal_id: str, session_id: str) -> None:
        self.closed.append((principal_id, session_id))


class FakeStreamResponse:
    def __init__(self, chunks, *, headers=None, status_code=200):
        self.chunks = chunks
        self.headers = headers or {}
        self.status_code = status_code
        self.iterated = False
        self.bytes_yielded = 0

    def raise_for_status(self):
        if self.status_code >= 400:
            raise RuntimeError("Unexpected fake status")

    async def aiter_bytes(self, chunk_size=4096):
        self.iterated = True
        for source in self.chunks:
            for offset in range(0, len(source), chunk_size):
                chunk = source[offset : offset + chunk_size]
                self.bytes_yielded += len(chunk)
                yield chunk


class FakeStreamContext:
    def __init__(self, response):
        self.response = response
        self.closed = False

    async def __aenter__(self):
        return self.response

    async def __aexit__(self, exc_type, exc, traceback):
        self.closed = True
        return False


class FakeHttpClient:
    def __init__(self, response):
        self.response = response
        self.calls = []
        self.stream_context = None

    def stream(self, method, url, **kwargs):
        self.calls.append((method, url, kwargs))
        self.stream_context = FakeStreamContext(self.response)
        return self.stream_context

    async def aclose(self):
        pass


def make_client(upstream_response):
    settings = Settings(
        openai_api_key="sk-test-only",
        auth_jwks_url="https://issuer.example/.well-known/jwks.json",
        auth_issuer="https://issuer.example/",
        auth_audience="galaxy-android",
        safety_identifier_hmac_key="test-hmac-secret-long-enough",
        allowed_voices=frozenset({"alloy"}),
        allowed_character_ids=frozenset({"galaxy.default"}),
    )
    http_client = FakeHttpClient(upstream_response)
    session_registry = FakeSessionRegistry()
    app = create_app(
        settings=settings,
        http_client=http_client,
        authenticator=FakeAuthenticator(),
        session_registry=session_registry,
    )
    return TestClient(app), http_client, session_registry


def create_session(client):
    return client.post(
        "/v1/realtime/sessions",
        headers={"Authorization": "Bearer valid-token"},
        json={"character_id": "galaxy.default", "voice_id": "alloy"},
    )


def test_realtime_session_accepts_bounded_streamed_json_response():
    body = json.dumps(
        {
            "value": "ephemeral-client-secret",
            "expires_at": 1_764_000_000,
            "session": {"id": "provider-session"},
        }
    ).encode("utf-8")
    upstream = FakeStreamResponse(
        [body],
        headers={"content-length": str(len(body))},
    )
    client, http_client, registry = make_client(upstream)

    with client:
        response = create_session(client)

    assert response.status_code == 200
    assert response.json()["client_secret"] == "ephemeral-client-secret"
    assert response.json()["expires_at_epoch_millis"] == 1_764_000_000_000
    assert len(registry.reserved) == 1
    assert registry.closed == []
    assert http_client.calls[0][0] == "POST"
    assert http_client.calls[0][1] == "https://api.openai.com/v1/realtime/client_secrets"
    assert http_client.stream_context.closed is True


def test_realtime_session_rejects_oversized_declared_content_length_before_reading():
    upstream = FakeStreamResponse(
        [b'{"value":"must-not-be-read"}'],
        headers={"content-length": str(MAX_REALTIME_SESSION_RESPONSE_BYTES + 1)},
    )
    client, http_client, registry = make_client(upstream)

    with client:
        response = create_session(client)

    assert response.status_code == 502
    assert "must-not-be-read" not in response.text
    assert upstream.iterated is False
    assert http_client.stream_context.closed is True
    assert registry.closed == registry.reserved


def test_realtime_session_rejects_oversized_chunked_body_and_closes_lease():
    body = (
        b'{"value":"SENSITIVE-RESPONSE-MARKER",'
        + b"x" * (MAX_REALTIME_SESSION_RESPONSE_BYTES + 8192)
        + b'"}'
    )
    upstream = FakeStreamResponse([body])
    client, http_client, registry = make_client(upstream)

    with client:
        response = create_session(client)

    assert response.status_code == 502
    assert "SENSITIVE-RESPONSE-MARKER" not in response.text
    assert upstream.iterated is True
    assert upstream.bytes_yielded <= MAX_REALTIME_SESSION_RESPONSE_BYTES + 4096
    assert http_client.stream_context.closed is True
    assert registry.closed == registry.reserved
