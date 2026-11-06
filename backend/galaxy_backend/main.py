import logging
import time
import uuid
from contextlib import asynccontextmanager
from typing import Annotated, Any

import httpx
from fastapi import Depends, FastAPI, Header, HTTPException, Request, status
from pydantic import BaseModel, Field
from redis.asyncio import Redis

from .config import Settings
from .security import InMemoryRateLimiter, JwtAuthenticator, RedisRateLimiter, safety_identifier

logger = logging.getLogger("galaxy.request")

SERVER_INSTRUCTIONS = (
    "You are Galaxy, a helpful conversational AI companion. "
    "Speak naturally, clearly, and respectfully. Follow safety requirements."
)


class CreateSessionRequest(BaseModel):
    character_id: str = Field(min_length=1, max_length=128)
    voice_id: str = Field(min_length=1, max_length=64)


class CreateSessionResponse(BaseModel):
    client_secret: str
    expires_at_epoch_millis: int
    session_id: str


def create_app(
    settings: Settings | None = None,
    http_client: httpx.AsyncClient | None = None,
    authenticator: JwtAuthenticator | None = None,
    rate_limiter: Any | None = None,
) -> FastAPI:
    @asynccontextmanager
    async def lifespan(app: FastAPI):
        app.state.settings = settings or Settings.from_env()
        app.state.http_client = http_client or httpx.AsyncClient(timeout=httpx.Timeout(15.0, connect=5.0))
        app.state.owns_http_client = http_client is None
        app.state.authenticator = authenticator or JwtAuthenticator(app.state.settings)
        app.state.redis = None

        if rate_limiter is not None:
            app.state.rate_limiter = rate_limiter
        elif app.state.settings.redis_url:
            redis_client = Redis.from_url(
                app.state.settings.redis_url,
                encoding="utf-8",
                decode_responses=True,
                socket_connect_timeout=2,
                socket_timeout=2,
                health_check_interval=30,
            )
            try:
                await redis_client.ping()
            except Exception:
                await redis_client.aclose()
                raise RuntimeError("Redis is required for the configured rate limiter but is unavailable")
            app.state.redis = redis_client
            app.state.rate_limiter = RedisRateLimiter(
                redis_client, app.state.settings.per_user_requests_per_minute
            )
        elif app.state.settings.environment == "production":
            raise RuntimeError("Production requires Redis-backed rate limiting")
        else:
            app.state.rate_limiter = InMemoryRateLimiter(
                app.state.settings.per_user_requests_per_minute
            )

        yield
        if app.state.redis is not None:
            await app.state.redis.aclose()
        if app.state.owns_http_client:
            await app.state.http_client.aclose()

    app = FastAPI(title="Galaxy Backend", version="0.2.0", lifespan=lifespan)

    @app.middleware("http")
    async def request_logging(request: Request, call_next):
        request_id = request.headers.get("X-Request-ID", "").strip()
        if not request_id or len(request_id) > 128 or any(ord(c) < 33 or ord(c) > 126 for c in request_id):
            request_id = uuid.uuid4().hex
        started = time.perf_counter()
        status_code = 500
        try:
            response = await call_next(request)
            status_code = response.status_code
            response.headers["X-Request-ID"] = request_id
            return response
        finally:
            logger.info(
                "request_complete request_id=%s method=%s path=%s status=%s duration_ms=%s",
                request_id, request.method, request.url.path, status_code,
                round((time.perf_counter() - started) * 1000, 2),
            )

    async def current_user(
        request: Request,
        authorization: Annotated[str | None, Header()] = None,
    ) -> str:
        if not authorization or not authorization.startswith("Bearer "):
            raise HTTPException(
                status_code=status.HTTP_401_UNAUTHORIZED,
                detail="Bearer access token required",
                headers={"WWW-Authenticate": "Bearer"},
            )
        return request.app.state.authenticator.authenticate(authorization[7:])

    @app.get("/health")
    async def health():
        return {"status": "ok"}

    @app.get("/ready")
    async def ready(request: Request):
        redis_client = request.app.state.redis
        if redis_client is not None:
            try:
                await redis_client.ping()
            except Exception as exc:
                raise HTTPException(status_code=503, detail="Redis readiness check failed") from exc
        return {"status": "ready"}

    @app.post("/v1/realtime/sessions", response_model=CreateSessionResponse)
    async def create_realtime_session(
        payload: CreateSessionRequest,
        request: Request,
        user_id: Annotated[str, Depends(current_user)],
    ):
        config: Settings = request.app.state.settings
        if payload.character_id not in config.allowed_character_ids:
            raise HTTPException(status_code=422, detail="Requested character is not available")
        if payload.voice_id not in config.allowed_voices:
            raise HTTPException(status_code=422, detail="Requested voice is not available")

        await request.app.state.rate_limiter.check(user_id)

        provider_body = {
            "expires_after": {
                "anchor": "created_at",
                "seconds": config.client_secret_ttl_seconds,
            },
            "session": {
                "type": "realtime",
                "model": config.realtime_model,
                "instructions": SERVER_INSTRUCTIONS,
                "output_modalities": ["audio"],
                "audio": {"output": {"voice": payload.voice_id}},
            },
        }
        headers = {
            "Authorization": f"Bearer {config.openai_api_key}",
            "Content-Type": "application/json",
            "OpenAI-Safety-Identifier": safety_identifier(
                user_id, config.safety_identifier_hmac_key
            ),
        }

        try:
            response = await request.app.state.http_client.post(
                "https://api.openai.com/v1/realtime/client_secrets",
                json=provider_body,
                headers=headers,
            )
            response.raise_for_status()
            data = response.json()
            secret = data.get("value")
            expires_at = data.get("expires_at")
            session_id = (data.get("session") or {}).get("id")
            if not isinstance(secret, str) or not secret or not isinstance(expires_at, int) or not isinstance(session_id, str) or not session_id:
                raise ValueError("Unexpected provider response")
        except (httpx.HTTPError, ValueError, TypeError, AttributeError) as exc:
            raise HTTPException(
                status_code=502,
                detail="Realtime session provider is temporarily unavailable",
            ) from exc

        return CreateSessionResponse(
            client_secret=secret,
            expires_at_epoch_millis=expires_at * 1000,
            session_id=session_id,
        )

    return app


app = create_app()
