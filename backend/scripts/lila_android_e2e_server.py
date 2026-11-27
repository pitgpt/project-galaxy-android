from __future__ import annotations

import json
import os
import ssl
import uuid
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from threading import Lock, Thread
from time import monotonic, time
from urllib.parse import urlsplit

import jwt
import uvicorn
from cryptography.hazmat.primitives.asymmetric import rsa

from galaxy_backend.config import Settings
from galaxy_backend.lila_core.provider_egress import create_provider_http_client
from galaxy_backend.main import create_app


MAX_DIAGNOSTIC_BYTES = 16 * 1024


def _safe_route_template(request) -> str:
    route = request.scope.get("route")
    template = getattr(route, "path", None)
    if isinstance(template, str) and template.startswith("/v1/lila/") and len(template) <= 128:
        return template
    return "/unmatched"


def _diagnostic_category(status: int, exception: Exception | None = None) -> str:
    if exception is not None:
        if isinstance(exception, ssl.SSLError):
            return "tls_error"
        if isinstance(exception, TimeoutError):
            return "timeout"
        if isinstance(exception, ConnectionError):
            return "connection_error"
        if isinstance(exception, OSError):
            return "io_error"
        return "internal_error"
    if status in (401, 403):
        return "authentication"
    if status == 404:
        return "not_found"
    if status == 422:
        return "validation"
    if status == 429:
        return "rate_limited"
    if status >= 500:
        return "server_error"
    if status >= 400:
        return "client_error"
    return "ok"


def _append_bounded_diagnostic(path: Path, lock: Lock, record: dict[str, object]) -> None:
    encoded = (json.dumps(record, separators=(",", ":"), sort_keys=True) + "\n").encode("utf-8")
    with lock:
        try:
            current_size = path.stat().st_size
        except FileNotFoundError:
            current_size = 0
        if current_size + len(encoded) > MAX_DIAGNOSTIC_BYTES:
            return
        descriptor = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_APPEND, 0o600)
        try:
            os.chmod(path, 0o600)
            with os.fdopen(descriptor, "ab", closefd=False) as output:
                output.write(encoded)
                output.flush()
        finally:
            os.close(descriptor)


class _JwksHandler(BaseHTTPRequestHandler):
    jwks: dict[str, object] = {}

    def do_GET(self) -> None:
        if self.path != "/.well-known/jwks.json":
            self.send_error(404)
            return
        payload = json.dumps(self.jwks, separators=(",", ":")).encode("utf-8")
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(payload)))
        self.end_headers()
        self.wfile.write(payload)

    def log_message(self, format: str, *args: object) -> None:
        return


def main() -> None:
    ollama_url = os.environ["LILA_LIVE_OLLAMA_URL"].rstrip("/")
    llama_cpp_url = os.environ["LILA_LIVE_LLAMA_CPP_URL"].rstrip("/")
    model_id = os.environ["LILA_LIVE_OLLAMA_MODEL"]
    llama_cpp_model_id = os.environ["LILA_LIVE_LLAMA_CPP_MODEL"]
    comfyui_url = os.environ["LILA_LIVE_COMFYUI_URL"].rstrip("/")
    redis_url = os.environ["REDIS_URL"]
    tls_cert = os.environ["LILA_ANDROID_E2E_TLS_CERT"]
    tls_key = os.environ["LILA_ANDROID_E2E_TLS_KEY"]
    config_path = Path(os.environ["LILA_ANDROID_E2E_CONFIG_PATH"])
    runner_temp = Path(os.environ["RUNNER_TEMP"])
    diagnostics_path = runner_temp / "lila-android-e2e" / "requests.jsonl"
    provider_requests_path = runner_temp / "lila-android-e2e" / "provider-requests.jsonl"
    diagnostics_path.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
    diagnostics_path.write_bytes(b"")
    provider_requests_path.write_bytes(b"")
    os.chmod(diagnostics_path, 0o600)
    os.chmod(provider_requests_path, 0o600)
    diagnostics_lock = Lock()
    provider_diagnostics_lock = Lock()

    parsed = urlsplit(ollama_url)
    if (
        parsed.scheme,
        parsed.hostname,
        parsed.port,
        parsed.path,
        parsed.query,
        parsed.fragment,
    ) != ("http", "127.0.0.1", 11434, "", "", ""):
        raise RuntimeError("The Android E2E native model endpoint must be the isolated local Ollama service")
    parsed_llama_cpp = urlsplit(llama_cpp_url)
    if (
        parsed_llama_cpp.scheme,
        parsed_llama_cpp.hostname,
        parsed_llama_cpp.port,
        parsed_llama_cpp.path,
        parsed_llama_cpp.query,
        parsed_llama_cpp.fragment,
    ) != ("http", "127.0.0.1", 8080, "/v1", "", ""):
        raise RuntimeError("The Android E2E llama.cpp endpoint must use the isolated local server")
    parsed_comfyui = urlsplit(comfyui_url)
    if (
        parsed_comfyui.scheme,
        parsed_comfyui.hostname,
        parsed_comfyui.port,
        parsed_comfyui.path,
        parsed_comfyui.query,
        parsed_comfyui.fragment,
    ) != ("http", "127.0.0.1", 8188, "", "", ""):
        raise RuntimeError("The Android E2E ComfyUI endpoint must use the isolated local CPU server")
    if (
        not model_id.strip()
        or not llama_cpp_model_id.strip()
        or not Path(tls_cert).is_file()
        or not Path(tls_key).is_file()
    ):
        raise RuntimeError("The Android E2E runtime configuration is incomplete")

    private_key = rsa.generate_private_key(public_exponent=65537, key_size=2048)
    key_id = uuid.uuid4().hex
    public_jwk = json.loads(jwt.algorithms.RSAAlgorithm.to_jwk(private_key.public_key()))
    public_jwk.update({"kid": key_id, "use": "sig", "alg": "RS256"})
    _JwksHandler.jwks = {"keys": [public_jwk]}

    jwks_server = ThreadingHTTPServer(("127.0.0.1", 0), _JwksHandler)
    Thread(target=jwks_server.serve_forever, daemon=True).start()

    issuer = "https://lila-android-live.example/"
    audience = "galaxy-android"
    owner_key = "galaxy-android-" + uuid.uuid4().hex
    now = int(time())
    access_token = jwt.encode(
        {
            "sub": owner_key,
            "iss": issuer,
            "aud": audience,
            "iat": now,
            "exp": now + 3600,
        },
        private_key,
        algorithm="RS256",
        headers={"kid": key_id},
    )

    provider_id = "ollama-android-" + uuid.uuid4().hex[:12]
    llama_cpp_provider_id = "llama-cpp-android-" + uuid.uuid4().hex[:12]
    database_directory = runner_temp / "lila-android-e2e-private"
    database_directory.mkdir(mode=0o700, parents=True, exist_ok=True)
    os.chmod(database_directory, 0o700)
    database_path = database_directory / "lila-android-e2e.sqlite3"
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
        lila_database_path=str(database_path),
        lila_storage_encryption_key=os.urandom(32),
        lila_system_prompt=(
            "You are Lila, a concise and friendly Russian-language AI companion. "
            "Answer naturally and briefly."
        ),
    )

    config_path.parent.mkdir(parents=True, exist_ok=True)
    config_payload = {
        "base_url": "https://127.0.0.1:8443",
        "access_token": access_token,
        "owner_key": owner_key,
        "character_id": "galaxy.default",
        "provider_id": provider_id,
        "provider_base_url": ollama_url,
        "llama_cpp_provider_id": llama_cpp_provider_id,
        "second_provider_base_url": llama_cpp_url,
        "model_id": model_id,
        "second_model_id": llama_cpp_model_id,
        "comfyui_base_url": comfyui_url,
    }
    descriptor = os.open(config_path, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
    with os.fdopen(descriptor, "w", encoding="utf-8") as output:
        json.dump(config_payload, output, separators=(",", ":"))
        output.flush()
        os.fsync(output.fileno())

    def recording_provider_http_client_factory(provider_type, timeout_seconds):
        client = create_provider_http_client(provider_type, timeout_seconds)

        async def record_provider_request(request):
            # Route metadata only: never capture headers, request bodies, prompts,
            # provider responses, or credentials.
            _append_bounded_diagnostic(
                provider_requests_path,
                provider_diagnostics_lock,
                {
                    "method": request.method,
                    "port": request.url.port,
                    "path": request.url.path,
                },
            )

        client.event_hooks["request"].append(record_provider_request)
        return client

    app = create_app(
        settings=settings,
        lila_provider_http_client_factory=recording_provider_http_client_factory,
    )

    @app.middleware("http")
    async def safe_e2e_request_diagnostics(request, call_next):
        request_id = request.headers.get("X-Request-ID", "").strip()
        if (
            not request_id
            or len(request_id) > 128
            or any(ord(character) < 33 or ord(character) > 126 for character in request_id)
        ):
            request_id = uuid.uuid4().hex
        started = monotonic()
        status_code = 500
        category = "internal_error"
        try:
            response = await call_next(request)
            status_code = response.status_code
            category = _diagnostic_category(status_code)
            response.headers["X-Request-ID"] = request_id
            return response
        except Exception as exception:
            category = _diagnostic_category(status_code, exception)
            raise
        finally:
            _append_bounded_diagnostic(
                diagnostics_path,
                diagnostics_lock,
                {
                    "request_id": request_id,
                    "method": request.method,
                    "route": _safe_route_template(request),
                    "status": status_code,
                    "category": category,
                    "duration_ms": round((monotonic() - started) * 1000, 2),
                },
            )

    try:
        uvicorn.run(
            app,
            host="0.0.0.0",
            port=8443,
            ssl_certfile=tls_cert,
            ssl_keyfile=tls_key,
            log_level="warning",
            access_log=False,
        )
    finally:
        jwks_server.shutdown()
        jwks_server.server_close()
        try:
            config_path.unlink()
        except FileNotFoundError:
            pass


if __name__ == "__main__":
    main()
