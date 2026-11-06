# Galaxy Backend

A separate FastAPI service for authenticated, short-lived Realtime client secrets.

## Run locally

Python 3.11+ is recommended.

```bash
cd backend
python -m venv .venv
source .venv/bin/activate
pip install -r requirements.txt
export OPENAI_API_KEY="..."
export AUTH_JWKS_URL="https://YOUR_AUTH_ISSUER/.well-known/jwks.json"
export AUTH_ISSUER="https://YOUR_AUTH_ISSUER/"
export AUTH_AUDIENCE="galaxy-android"
export SAFETY_IDENTIFIER_HMAC_KEY="$(python -c 'import secrets; print(secrets.token_urlsafe(48))')"
uvicorn galaxy_backend.main:app --host 127.0.0.1 --port 8000
```

The auth issuer, audience and JWKS URL must belong to the authentication provider selected for Galaxy. The service deliberately fails closed when those values or secrets are missing. Do not use a fake token verifier in production.

## API

`POST /v1/realtime/sessions`

Requires `Authorization: Bearer <JWT>`.

Request body:

```json
{"character_id":"galaxy.default","voice_id":"alloy"}
```

The backend verifies the JWT signature (RS256), issuer, audience, expiry and subject. It then applies a per-user request limit, validates the character against `GALAXY_ALLOWED_CHARACTER_IDS` and the voice against `GALAXY_ALLOWED_VOICES`, and creates a short-lived client secret using the server-only OpenAI API key.

The client cannot choose the model, instructions, tools, or arbitrary provider session fields. The character ID is currently an opaque application identifier; character-to-instruction policy is server-owned and must be added before custom character instructions are supported.

Response contains only `client_secret`, `expires_at_epoch_millis`, and `session_id`. Treat the client secret as sensitive and memory-only.

## Configuration

- `OPENAI_API_KEY`: server-side secret; never put this in Android or source control.
- `AUTH_JWKS_URL`, `AUTH_ISSUER`, `AUTH_AUDIENCE`: selected identity provider's JWT validation settings.
- `SAFETY_IDENTIFIER_HMAC_KEY`: independent random secret used to derive privacy-preserving OpenAI safety identifiers.
- `GALAXY_ALLOWED_VOICES`: comma-separated voice allow-list.
- `GALAXY_ALLOWED_CHARACTER_IDS`: comma-separated character ID allow-list; defaults to `galaxy.default`.
- `REALTIME_MODEL`: server-selected model; defaults to `gpt-realtime`.
- `REALTIME_CLIENT_SECRET_TTL_SECONDS`: 10–7200 seconds; defaults to 600.
- `REALTIME_REQUESTS_PER_MINUTE`: per-process limit; defaults to 5.

## Container

Build and run with Docker:

```bash
docker build -t galaxy-backend .
docker run --rm -p 8000:8000 --env-file .env galaxy-backend
```

Create `.env` from the required configuration above. Never commit it. The image runs as a non-root user and exposes the service on port 8000. Put it behind a TLS-terminating reverse proxy or managed HTTPS ingress.

## Production hardening still required

The included rate limiter is process-local and resets on restart. Replace it with a shared Redis-backed limiter before running multiple replicas or exposing the service publicly. Deploy behind HTTPS, add account/session concurrency and usage ceilings, configure secret rotation and monitoring, and choose a real identity provider before connecting Android. No production authentication provider or deployment is configured by this repository yet.

Run tests with `pytest`.
