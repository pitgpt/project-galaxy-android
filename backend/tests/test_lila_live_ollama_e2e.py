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
def test_live_ollama_memory_tenant_isolation_and_history_survive_production_restart(
    tmp_path,
):
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

    def token_for(subject: str) -> str:
        return jwt.encode(
            {
                "sub": subject,
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

    captured_chat_requests: list[dict[str, object]] = []
    captured_provider_routes: list[dict[str, object]] = []

    def recording_provider_http_client_factory(provider_type, timeout_seconds):
        client = create_provider_http_client(provider_type, timeout_seconds)

        async def capture_provider_request(request):
            # Keep only non-secret routing metadata. Never retain prompt bodies,
            # authorization headers, response payloads, or provider diagnostics.
            captured_provider_routes.append(
                {
                    "method": request.method,
                    "port": request.url.port,
                    "path": request.url.path,
                }
            )
            if request.url.path == "/api/chat":
                captured_chat_requests.append(json.loads(request.content))

        client.event_hooks["request"].append(capture_provider_request)
        return client

    def new_app():
        return create_app(
            settings=settings,
            lila_provider_http_client_factory=recording_provider_http_client_factory,
        )

    auth_headers = {"Authorization": "Bearer " + token_for("live-ollama-owner")}
    other_owner_headers = {
        "Authorization": "Bearer " + token_for("live-ollama-other-owner")
    }
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
    memory_id = "project-codeword-" + uuid.uuid4().hex
    memory_content = (
        "The project codeword is violet-comet-731. "
        "When asked for the project codeword, answer exactly violet-comet-731."
    )

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

            saved_memory = client.put(
                "/v1/lila/memories/" + memory_id,
                headers=auth_headers,
                json={"content": memory_content},
            )
            assert saved_memory.status_code == 200, saved_memory.text
            assert saved_memory.json() == {"memory_id": memory_id, "saved": True}

            other_owner_memory = client.get(
                "/v1/lila/memories/search",
                headers=other_owner_headers,
                params={"query": "project codeword"},
            )
            assert other_owner_memory.status_code == 200, other_owner_memory.text
            assert other_owner_memory.json()["memories"] == []

            other_owner_history = client.get(
                "/v1/lila/conversations/" + conversation_id + "/messages",
                headers=other_owner_headers,
            )
            assert other_owner_history.status_code == 200, other_owner_history.text
            assert other_owner_history.json()["messages"] == []

            other_owner_selection = client.get(
                "/v1/lila/conversations/" + conversation_id + "/model",
                headers=other_owner_headers,
            )
            assert other_owner_selection.status_code == 200, other_owner_selection.text
            assert other_owner_selection.json()["selected"] is False

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
            assert len(captured_chat_requests) == 1
            assert not any(
                memory_content in item.get("content", "")
                for item in captured_chat_requests[0]["messages"]
            )

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

            restored_memory = client.get(
                "/v1/lila/memories/search",
                headers=auth_headers,
                params={"query": "project codeword"},
            )
            assert restored_memory.status_code == 200, restored_memory.text
            assert restored_memory.json()["memories"] == [
                {"memory_id": memory_id, "content": memory_content}
            ]

            second_turn = client.post(
                "/v1/lila/turns",
                headers={
                    **auth_headers,
                    "Idempotency-Key": str(uuid.uuid4()),
                },
                json={
                    "conversation_id": conversation_id,
                    "message": "What is the project codeword?",
                },
            )
            assert second_turn.status_code == 200, second_turn.text
            assert second_turn.json()["text"].strip()
            assert second_turn.json()["provider_id"] == provider_id
            assert second_turn.json()["model_id"] == model_id

            assert len(captured_chat_requests) == 2
            owner_messages = captured_chat_requests[1]["messages"]
            memory_messages = [
                item
                for item in owner_messages
                if item.get("role") == "user"
                and "Untrusted memory excerpts for factual reference only"
                in item.get("content", "")
            ]
            assert len(memory_messages) == 1
            assert memory_content in memory_messages[0]["content"]
            assert any(
                item.get("role") == "user"
                and item.get("content") == first_messages[0]["content"]
                for item in owner_messages
            )
            assert any(
                item.get("role") == "assistant"
                and item.get("content") == first_turn.json()["text"]
                for item in owner_messages
            )

            # A second authenticated owner may use the same opaque conversation
            # and provider IDs, but must receive neither the first owner's
            # history nor their durable memory in the actual model request.
            other_created = client.post(
                "/v1/lila/providers",
                headers=other_owner_headers,
                json=provider_payload,
            )
            assert other_created.status_code == 200, other_created.text

            other_selected = client.put(
                "/v1/lila/conversations/" + conversation_id + "/model",
                headers=other_owner_headers,
                json={"provider_id": provider_id, "model_id": model_id},
            )
            assert other_selected.status_code == 200, other_selected.text
            assert other_selected.json()["selected"] is True

            other_turn = client.post(
                "/v1/lila/turns",
                headers={
                    **other_owner_headers,
                    "Idempotency-Key": str(uuid.uuid4()),
                },
                json={
                    "conversation_id": conversation_id,
                    "message": "What is the project codeword?",
                },
            )
            assert other_turn.status_code == 200, other_turn.text
            assert other_turn.json()["provider_id"] == provider_id
            assert other_turn.json()["model_id"] == model_id

            assert len(captured_chat_requests) == 3
            other_messages = captured_chat_requests[2]["messages"]
            assert not any(
                memory_content in item.get("content", "")
                for item in other_messages
            )
            assert not any(
                item.get("content") == first_messages[0]["content"]
                or item.get("content") == first_turn.json()["text"]
                for item in other_messages
            )
            assert any(
                item.get("role") == "user"
                and item.get("content") == "What is the project codeword?"
                for item in other_messages
            )

            other_final_history = client.get(
                "/v1/lila/conversations/" + conversation_id + "/messages",
                headers=other_owner_headers,
            )
            assert other_final_history.status_code == 200, other_final_history.text
            assert [item["role"] for item in other_final_history.json()["messages"]] == [
                "user",
                "assistant",
            ]
            assert other_final_history.json()["messages"][0]["content"] == (
                "What is the project codeword?"
            )

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
            assert final_messages[2]["content"] == "What is the project codeword?"
            assert final_messages[-1]["provider_id"] == provider_id
            assert final_messages[-1]["model_id"] == model_id

            # Prove the production DNS-pinned HTTPX transport classifies a real
            # TCP refusal as a safe pre-send failure after the primary passed
            # model discovery. The fallback is the already-running real Ollama.
            probe_model_id = "tcp-probe-model"
            primary_probe_provider_id = "tcp-primary-" + uuid.uuid4().hex[:12]
            primary_probe_requests = []

            class PrimaryProbeHandler(BaseHTTPRequestHandler):
                def do_GET(self):
                    if self.path != "/v1/models":
                        self.send_error(404)
                        return
                    primary_probe_requests.append(("GET", self.path))
                    payload = json.dumps(
                        {
                            "object": "list",
                            "data": [{"id": probe_model_id, "object": "model"}],
                        },
                        separators=(",", ":"),
                    ).encode("utf-8")
                    self.send_response(200)
                    self.send_header("Content-Type", "application/json")
                    self.send_header("Content-Length", str(len(payload)))
                    self.end_headers()
                    self.wfile.write(payload)

                def do_POST(self):
                    primary_probe_requests.append(("POST", self.path))
                    self.send_error(500)

                def log_message(self, format, *args):
                    return

            primary_probe_server = ThreadingHTTPServer(
                ("127.0.0.1", 8080),
                PrimaryProbeHandler,
            )
            primary_probe_thread = threading.Thread(
                target=primary_probe_server.serve_forever,
                daemon=True,
            )
            primary_probe_thread.start()
            try:
                primary_probe_payload = {
                    "provider_id": primary_probe_provider_id,
                    "provider_type": "local",
                    "base_url": "http://127.0.0.1:8080/v1",
                    "model_id": probe_model_id,
                    "api_key": None,
                    "timeout_seconds": 5,
                    "capabilities": ["chat"],
                    "enabled": True,
                }
                primary_created = client.post(
                    "/v1/lila/providers",
                    headers=auth_headers,
                    json=primary_probe_payload,
                )
                assert primary_created.status_code == 200, primary_created.text

                primary_probe = client.post(
                    "/v1/lila/providers/" + primary_probe_provider_id + "/probe",
                    headers=auth_headers,
                )
                assert primary_probe.status_code == 200, primary_probe.text
                assert primary_probe.json()["connection_reachable"] is True
                assert primary_probe.json()["configured_model_available"] is True
                assert primary_probe_requests == [("GET", "/v1/models")]

                selected_tcp_fallback = client.put(
                    "/v1/lila/conversations/" + conversation_id + "/model",
                    headers=auth_headers,
                    json={
                        "provider_id": primary_probe_provider_id,
                        "model_id": probe_model_id,
                        "fallback_provider_id": provider_id,
                        "fallback_model_id": model_id,
                    },
                )
                assert selected_tcp_fallback.status_code == 200, selected_tcp_fallback.text
                assert selected_tcp_fallback.json()["selected"] is True
                assert selected_tcp_fallback.json()["fallback_provider_id"] == provider_id
                assert selected_tcp_fallback.json()["fallback_model_id"] == model_id
            finally:
                # Close the listener only after the successful discovery probe.
                # The next generation must therefore fail during TCP connect,
                # before any HTTP request can reach this primary provider.
                primary_probe_server.shutdown()
                primary_probe_server.server_close()
                primary_probe_thread.join(timeout=2)

            assert ("GET", 8080, "/v1/models") in [
                (item["method"], item["port"], item["path"])
                for item in captured_provider_routes
            ]

            tcp_fallback_message = (
                "Проверь резервирование после отказа TCP-соединения основной модели."
            )
            tcp_fallback = client.post(
                "/v1/lila/turns",
                headers={
                    **auth_headers,
                    "Idempotency-Key": str(uuid.uuid4()),
                },
                json={
                    "conversation_id": conversation_id,
                    "message": tcp_fallback_message,
                },
            )
            assert tcp_fallback.status_code == 200, tcp_fallback.text
            tcp_fallback_result = tcp_fallback.json()
            assert tcp_fallback_result["text"].strip()
            assert tcp_fallback_result["provider_id"] == provider_id
            assert tcp_fallback_result["model_id"] == model_id
            assert tcp_fallback_result["fallback_used"] is True
            assert tcp_fallback_result["fallback_from_provider_id"] == primary_probe_provider_id
            assert tcp_fallback_result["fallback_from_model_id"] == probe_model_id
            assert tcp_fallback_result["fallback_reason"] == "primary_connection_unavailable"

            route_attempts = [
                (item["method"], item["port"], item["path"])
                for item in captured_provider_routes
            ]
            primary_generation_attempt = (
                "POST",
                8080,
                "/v1/chat/completions",
            )
            fallback_generation_request = ("POST", 11434, "/api/chat")
            assert primary_generation_attempt in route_attempts
            assert fallback_generation_request in route_attempts
            assert route_attempts.index(primary_generation_attempt) < max(
                index
                for index, route in enumerate(route_attempts)
                if route == fallback_generation_request
            )
            # Selection validation may repeat the non-generative model probe.
            # Every request that reached the listener must remain a GET /models;
            # the generation attempt happens only after the listener is closed.
            assert primary_probe_requests
            assert all(
                method == "GET" and path == "/v1/models"
                for method, path in primary_probe_requests
            )

            tcp_fallback_history = client.get(
                "/v1/lila/conversations/" + conversation_id + "/messages",
                headers=auth_headers,
            )
            assert tcp_fallback_history.status_code == 200, tcp_fallback_history.text
            tcp_messages = tcp_fallback_history.json()["messages"]
            assert tcp_messages[-2]["role"] == "user"
            assert tcp_messages[-2]["content"] == tcp_fallback_message
            assert tcp_messages[-1]["role"] == "assistant"
            assert tcp_messages[-1]["provider_id"] == provider_id
            assert tcp_messages[-1]["model_id"] == model_id
            assert tcp_messages[-1]["fallback_used"] is True
            assert tcp_messages[-1]["fallback_from_provider_id"] == primary_probe_provider_id
            assert tcp_messages[-1]["fallback_from_model_id"] == probe_model_id
            assert tcp_messages[-1]["fallback_reason"] == "primary_connection_unavailable"

            assert len(captured_chat_requests) == 4
            assert any(
                item.get("role") == "user"
                and item.get("content") == tcp_fallback_message
                for item in captured_chat_requests[-1]["messages"]
            )

            deleted_primary = client.delete(
                "/v1/lila/providers/" + primary_probe_provider_id,
                headers=auth_headers,
            )
            assert deleted_primary.status_code == 200, deleted_primary.text
    finally:
        jwks_server.shutdown()
        jwks_server.server_close()
        jwks_thread.join(timeout=2)
