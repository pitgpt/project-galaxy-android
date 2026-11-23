from __future__ import annotations

import json
import os
import uuid
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from threading import Thread
from time import time
from urllib.parse import urlsplit

import jwt
import uvicorn
from cryptography.hazmat.primitives.asymmetric import rsa

from galaxy_backend.config import Settings
from galaxy_backend.main import create_app


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
    model_id = os.environ["LILA_LIVE_OLLAMA_MODEL"]
    redis_url = os.environ["REDIS_URL"]
    tls_cert = os.environ["LILA_ANDROID_E2E_TLS_CERT"]
    tls_key = os.environ["LILA_ANDROID_E2E_TLS_KEY"]
    config_path = Path(os.environ["LILA_ANDROID_E2E_CONFIG_PATH"])
    runner_temp = Path(os.environ["RUNNER_TEMP"])

    parsed = urlsplit(ollama_url)
    if (
        parsed.scheme,
        parsed.hostname,
        parsed.port,
        parsed.path,
        parsed.query,
        parsed.fragment,
    ) != ("http", "127.0.0.1", 11434, "", "", ""):
        raise RuntimeError("The Android E2E model endpoint must be the isolated local Ollama service")
    if not model_id.strip() or not Path(tls_cert).is_file() or not Path(tls_key).is_file():
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
    database_path = runner_temp / "lila-android-e2e.sqlite3"
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
        "base_url": "https://10.0.2.2:8443",
        "access_token": access_token,
        "owner_key": owner_key,
        "character_id": "galaxy.default",
        "provider_id": provider_id,
        "provider_base_url": ollama_url,
        "model_id": model_id,
    }
    descriptor = os.open(config_path, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
    with os.fdopen(descriptor, "w", encoding="utf-8") as output:
        json.dump(config_payload, output, separators=(",", ":"))
        output.flush()
        os.fsync(output.fileno())

    app = create_app(settings=settings)
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
