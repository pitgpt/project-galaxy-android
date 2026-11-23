from __future__ import annotations

import json
import os
import threading
import time
import uuid
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlsplit

import jwt
import pytest
from cryptography.hazmat.primitives.asymmetric import rsa
from fastapi.testclient import TestClient

from galaxy_backend.config import Settings
from galaxy_backend.main import create_app
from galaxy_backend.lila_core.provider_egress import create_provider_http_client


@pytest.mark.skipif(
    not all(
        os.getenv(name)
        for name in (
            "LILA_LIVE_OLLAMA_URL",
            "LILA_LIVE_OLLAMA_MODEL",
            "REDIS_URL",
        )
    ),
    reason="requires the dedicated live Ollama and Redis CI services",
)
def test_live_ollama_turn_and_encrypted_history_survive_production_restart(tmp_path):
    ollama_url = os.environ["LILA_LIVE_OLLAMA_URL"].rstrip("/")
    model_id = os.environ["LILA_LIVE_OLLAMA_MODEL"]
    redis_url = os.environ["REDIS_URL"]
    parsed = urlsplit(ollama_url)
    assert (
        parsed.scheme,
        parsed.hostname,
        parsed.port,
        parsed.path,
        parsed.query,
        parsed.fragment,
    ) == ("http", "127.0.0.1", 11434, "", "", "")
    assert model_id.strip()

    private_key = rsa.generate_private_key(public_exponent=65537, key_size=2048)
    key_id = uuid.uuid4().hex
    public_jwk = json.loads(
        jwt.algorithms.RSAAlgorithm.to_jwk(private_key.public_key())
    )
    public_jwk.update({"kid": key_id, "use": "sig", "alg": "RS256"})
    jwks = {"keys": [public_jwk]}

    class JwksHandler(BaseHTTPRequestHandler):
        def do_GET(self):
            if self.path != "/.well-known/jwks.json":
                self.send_error(404)
                return
            payload = json.dumps(jwks, separators=(",", ":")).encode("utf-8")
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(payload)))
            self.end_headers()
            self.wfile.write(payload)

        def log_message(self, format, *args):
            return

    jwks_server = ThreadingHTTPServer(("127.0.0.1", 0), JwksHandler)
    jwks_thread = threading.Thread(target=jwks_server.serve_forever, daemon=True)
    jwks_thread.start()

    issuer = "https://lila-live-e2e.example/"
    audience = "galaxy-android"
    now = int(time.time())
    access_token = jwt.encode(
        {
            "sub": "live-ollama-owner",
            "iss": issuer,
            "aud": audience,
            "iat": now,
            "exp": now + 600,
        },
        private_key,
        algorithm="RS256",
        headers={"kid": key_id},
    )
    settings = Settings(
        openai_api_key=None,
        auth_jwks_url=(
            "http://127.0.0.1:"
            + str(jwks_server.server_port)
            + "/.well-known/jwks.json"
        ),
        auth_issuer=issuer,
        auth_audience=audience,
        safety_identifier_hmac_key=None,
        allowed_voices=frozenset({"alloy"}),
        allowed_character_ids=frozenset({"galaxy.default"}),
        environment="production",
        redis_url=redis_url,
        lila_database_path=str(tmp_path / "lila-live.sqlite3"),
        lila_storage_encryption_key=os.urandom(32),
        lila_system_prompt=(
            "You are Lila, a concise and friendly Russian-language AI companion. "
            "Answer naturally and briefly."
        ),
    )

    def new_app():
        return create_app(
            settings=settings,
            lila_provider_http_client_factory=create_provider_http_client,
        )

    auth_headers = {"Authorization": "Bearer " + access_token}
    provider_id = "ollama-live"
    conversation_id = "live-" + uuid.uuid4().hex
    provider_payload = {
        "provider_id": provider_id,
        "provider_type": "local",
        "base_url": ollama_url,
        "model_id": model_id,
        "api_key": None,
        "timeout_seconds": 120,
        "capabilities": ["chat"],
        "enabled": True,
    }

    try:
        with TestClient(new_app()) as client:
            created = client.post(
                "/v1/lila/providers",
                headers=auth_headers,
                json=provider_payload,
            )
            assert created.status_code == 200, created.text
            assert created.json()["has_api_key"] is False
            assert "base_url" not in created.json()

            probe = client.post(
                "/v1/lila/providers/" + provider_id + "/probe",
                headers=auth_headers,
            )
            assert probe.status_code == 200, probe.text
            assert probe.json()["connection_reachable"] is True
            assert probe.json()["configured_model_available"] is True

            selected = client.put(
                "/v1/lila/conversations/" + conversation_id + "/model",
                headers=auth_headers,
                json={"provider_id": provider_id, "model_id": model_id},
            )
            assert selected.status_code == 200, selected.text
            assert selected.json()["selected"] is True

            first_turn = client.post(
                "/v1/lila/turns",
                headers={
                    **auth_headers,
                    "Idempotency-Key": str(uuid.uuid4()),
                },
                json={
                    "conversation_id": conversation_id,
                    "message": "Привет, Лила. Кратко представься.",
                },
            )
            assert first_turn.status_code == 200, first_turn.text
            assert first_turn.json()["text"].strip()
            assert first_turn.json()["provider_id"] == provider_id
            assert first_turn.json()["model_id"] == model_id

            first_history = client.get(
                "/v1/lila/conversations/" + conversation_id + "/messages",
                headers=auth_headers,
            )
            assert first_history.status_code == 200, first_history.text
            first_messages = first_history.json()["messages"]
            assert [item["role"] for item in first_messages] == [
                "user",
                "assistant",
            ]
            assert first_messages[-1]["provider_id"] == provider_id
            assert first_messages[-1]["model_id"] == model_id

        # A second production app instance proves the provider selection and
        # encrypted conversation history survive a real application restart.
        with TestClient(new_app()) as client:
            restored_selection = client.get(
                "/v1/lila/conversations/" + conversation_id + "/model",
                headers=auth_headers,
            )
            assert restored_selection.status_code == 200, restored_selection.text
            assert restored_selection.json()["selected"] is True
            assert restored_selection.json()["provider_id"] == provider_id
            assert restored_selection.json()["model_id"] == model_id

            restored_history = client.get(
                "/v1/lila/conversations/" + conversation_id + "/messages",
                headers=auth_headers,
            )
            assert restored_history.status_code == 200, restored_history.text
            assert restored_history.json()["messages"] == first_messages

            second_turn = client.post(
                "/v1/lila/turns",
                headers={
                    **auth_headers,
                    "Idempotency-Key": str(uuid.uuid4()),
                },
                json={
                    "conversation_id": conversation_id,
                    "message": "Продолжим разговор. Назови следующий шаг проекта.",
                },
            )
            assert second_turn.status_code == 200, second_turn.text
            assert second_turn.json()["text"].strip()
            assert second_turn.json()["provider_id"] == provider_id
            assert second_turn.json()["model_id"] == model_id

            final_history = client.get(
                "/v1/lila/conversations/" + conversation_id + "/messages",
                headers=auth_headers,
            )
            assert final_history.status_code == 200, final_history.text
            final_messages = final_history.json()["messages"]
            assert [item["role"] for item in final_messages] == [
                "user",
                "assistant",
                "user",
                "assistant",
            ]
            assert final_messages[0]["content"] == "Привет, Лила. Кратко представься."
            assert final_messages[2]["content"] == (
                "Продолжим разговор. Назови следующий шаг проекта."
            )
            assert final_messages[-1]["provider_id"] == provider_id
            assert final_messages[-1]["model_id"] == model_id
    finally:
        jwks_server.shutdown()
        jwks_server.server_close()
        jwks_thread.join(timeout=2)
