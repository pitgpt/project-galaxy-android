import logging
import time
import uuid
from contextlib import asynccontextmanager
from typing import Annotated, Any

import httpx
from fastapi import Depends, FastAPI, Header, HTTPException, Request, status
from pydantic import BaseModel, Field, ValidationError

from .lila_core.contracts import CoreTurnRequest, CoreTurnResponse
from .lila_core.context import ContextAssembler
from .lila_core.openai_compatible import (
    ModelProviderUnavailable,
    OpenAICompatibleConfig,
    OpenAICompatibleModelAdapter,
)
from .lila_core.service import CoreTurnService, ModelAdapterContractError
from .lila_core.sqlite_store import SqliteLilaStore
from redis.asyncio import Redis

from .config import Settings
from .security import (
    InMemoryLilaTurnRateLimiter,
    InMemoryRateLimiter,
    JwtAuthenticator,
    RedisLilaTurnRateLimiter,
    RedisRateLimiter,
    safety_identifier,
)
from .session_registry import InMemorySessionRegistry, RedisSessionRegistry

logger = logging.getLogger("galaxy.request")

VOICE_ID_ALIASES = {
    "voice.feminine_warm": "coral",
    "voice.feminine_calm": "shimmer",
    "voice.neutral": "alloy",
}


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
    session_registry: Any | None = None,
    lila_core_service: CoreTurnService | None = None,
    lila_turn_rate_limiter: Any | None = None,
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
                redis_client, app.state.settings.per_user_requests_per_minute,
                app.state.settings.per_user_sessions_per_day
            )
            app.state.session_registry = session_registry or RedisSessionRegistry(
                redis_client, app.state.settings.max_active_sessions,
                app.state.settings.session_lease_seconds
            )
        elif app.state.settings.environment == "production":
            raise RuntimeError("Production requires Redis-backed rate limiting")
        else:
            app.state.rate_limiter = InMemoryRateLimiter(
                app.state.settings.per_user_requests_per_minute,
                app.state.settings.per_user_sessions_per_day
            )
            app.state.session_registry = session_registry or InMemorySessionRegistry(
                app.state.settings.max_active_sessions,
                app.state.settings.session_lease_seconds
            )

        if app.state.settings.environment == "production":
            if app.state.redis is None:
                raise RuntimeError("Production requires Redis-backed Lila turn rate limiting")
            app.state.lila_turn_rate_limiter = RedisLilaTurnRateLimiter(
                app.state.redis,
                app.state.settings.lila_turns_per_minute,
                app.state.settings.lila_turns_per_day,
            )
        elif lila_turn_rate_limiter is not None:
            app.state.lila_turn_rate_limiter = lila_turn_rate_limiter
        elif app.state.redis is not None:
            app.state.lila_turn_rate_limiter = RedisLilaTurnRateLimiter(
                app.state.redis,
                app.state.settings.lila_turns_per_minute,
                app.state.settings.lila_turns_per_day,
            )
        else:
            app.state.lila_turn_rate_limiter = InMemoryLilaTurnRateLimiter(
                app.state.settings.lila_turns_per_minute,
                app.state.settings.lila_turns_per_day,
            )

        app.state.lila_store = None
        if (
            lila_core_service is None
            and app.state.settings.lila_configuration_requested
            and not app.state.settings.lila_is_configured
        ):
            raise RuntimeError("Lila configuration is incomplete")
        if lila_core_service is not None:
            app.state.lila_core_service = lila_core_service
        elif app.state.settings.lila_is_configured:
            store = SqliteLilaStore(
                app.state.settings.lila_database_path,
                app.state.settings.lila_storage_encryption_key,
            )
            await store.initialize()
            provider_config = OpenAICompatibleConfig(
                provider_id=app.state.settings.lila_provider_id,
                base_url=app.state.settings.lila_provider_base_url,
                model_id=app.state.settings.lila_provider_model_id,
                api_key=app.state.settings.lila_provider_api_key,
                timeout_seconds=app.state.settings.lila_provider_timeout_seconds,
                system_prompt=app.state.settings.lila_system_prompt,
            )
            adapter = OpenAICompatibleModelAdapter(
                app.state.http_client,
                provider_config,
            )
            assembler = ContextAssembler(store, store)
            app.state.lila_core_service = CoreTurnService(
                assembler,
                adapter,
                store,
            )
            app.state.lila_store = store
        elif app.state.settings.environment == "production":
            raise RuntimeError(
                "Production requires a configured Lila model provider and encrypted persistent storage"
            )
        else:
            app.state.lila_core_service = None

        yield
        if app.state.redis is not None:
            await app.state.redis.aclose()
        if app.state.owns_http_client:
            await app.state.http_client.aclose()

    app = FastAPI(title="Galaxy Backend", version="0.2.0", lifespan=lifespan)
    app.state.lila_core_service = lila_core_service

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

    @app.post("/v1/lila/turns", response_model=CoreTurnResponse)
    async def create_lila_turn(
        payload: CoreTurnRequest,
        request: Request,
        user_id: Annotated[str, Depends(current_user)],
    ):
        service: CoreTurnService | None = request.app.state.lila_core_service
        if service is None:
            raise HTTPException(
                status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
                detail="Lila Core is not configured",
            )
        try:
            await request.app.state.lila_turn_rate_limiter.check(user_id)
        except HTTPException:
            raise
        except Exception as exc:
            raise HTTPException(
                status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
                detail="Lila turn service temporarily unavailable",
                headers={"Retry-After": "5"},
            ) from exc

        try:
            return await service.handle(payload, principal_id=user_id)
        except ModelProviderUnavailable:
            raise HTTPException(
                status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
                detail="Lila model provider is temporarily unavailable",
                headers={"Retry-After": "5"},
            ) from None
        except (ModelAdapterContractError, ValidationError) as exc:
            raise HTTPException(
                status_code=status.HTTP_502_BAD_GATEWAY,
                detail="Lila model returned an invalid response",
            ) from exc
        except Exception as exc:
            # Do not expose provider, memory-store, or history-store details.
            # asyncio cancellation is a BaseException and remains propagatable.
            raise HTTPException(
                status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
                detail="Lila Core is temporarily unavailable",
            ) from exc

    @app.post("/v1/realtime/sessions", response_model=CreateSessionResponse)
    async def create_realtime_session(
        payload: CreateSessionRequest,
        request: Request,
        user_id: Annotated[str, Depends(current_user)],
    ):
        config: Settings = request.app.state.settings
        if not config.openai_api_key or not config.safety_identifier_hmac_key:
            raise HTTPException(
                status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
                detail="Realtime provider is not configured",
            )
        if payload.character_id not in config.allowed_character_ids:
            raise HTTPException(status_code=422, detail="Requested character is not available")
        provider_voice_id = VOICE_ID_ALIASES.get(payload.voice_id, payload.voice_id)
        if provider_voice_id not in config.allowed_voices:
            raise HTTPException(status_code=422, detail="Requested voice is not available")

        await request.app.state.rate_limiter.check(user_id)
        galaxy_session_id = uuid.uuid4().hex
        try:
            reserved = await request.app.state.session_registry.reserve(user_id, galaxy_session_id)
        except Exception as exc:
            raise HTTPException(status_code=503, detail="Realtime session service temporarily unavailable", headers={"Retry-After": "5"}) from exc
        if not reserved:
            raise HTTPException(status_code=429, detail="Maximum active realtime sessions reached", headers={"Retry-After": str(config.session_lease_seconds)})

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
                "audio": {"output": {"voice": provider_voice_id}},
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
            provider_session_id = (data.get("session") or {}).get("id")
            if not isinstance(secret, str) or not secret or not isinstance(expires_at, int) or not isinstance(provider_session_id, str) or not provider_session_id:
                raise ValueError("Unexpected provider response")
        except (httpx.HTTPError, ValueError, TypeError, AttributeError) as exc:
            try:
                await request.app.state.session_registry.close(user_id, galaxy_session_id)
            except Exception as cleanup_exc:
                logger.error("realtime_session_cleanup_failed")
                raise HTTPException(
                    status_code=503,
                    detail="Realtime session service temporarily unavailable",
                    headers={"Retry-After": "5"},
                ) from cleanup_exc
            raise HTTPException(
                status_code=502,
                detail="Realtime session provider is temporarily unavailable",
            ) from exc

        return CreateSessionResponse(
            client_secret=secret,
            expires_at_epoch_millis=expires_at * 1000,
            session_id=galaxy_session_id,
        )

    @app.post("/v1/realtime/sessions/{session_id}/heartbeat")
    async def heartbeat_realtime_session(
        session_id: str, request: Request,
        user_id: Annotated[str, Depends(current_user)],
    ):
        try:
            renewed = await request.app.state.session_registry.heartbeat(user_id, session_id)
        except Exception as exc:
            raise HTTPException(status_code=503, detail="Realtime session service temporarily unavailable", headers={"Retry-After": "5"}) from exc
        if not renewed:
            raise HTTPException(status_code=404, detail="Realtime session not found")
        return {"status": "active", "lease_seconds": request.app.state.settings.session_lease_seconds}

    @app.delete("/v1/realtime/sessions/{session_id}")
    async def close_realtime_session(
        session_id: str, request: Request,
        user_id: Annotated[str, Depends(current_user)],
    ):
        try:
            await request.app.state.session_registry.close(user_id, session_id)
        except Exception as exc:
            raise HTTPException(status_code=503, detail="Realtime session service temporarily unavailable", headers={"Retry-After": "5"}) from exc
        return {"status": "closed"}

    return app


app = create_app()
