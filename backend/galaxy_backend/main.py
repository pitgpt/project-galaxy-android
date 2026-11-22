import logging
import time
import uuid
from uuid import UUID
from contextlib import asynccontextmanager
from typing import Annotated, Any

import httpx
from fastapi import Depends, FastAPI, Header, HTTPException, Query, Request, status
from pydantic import BaseModel, Field, ValidationError

from .lila_core.contracts import (
    ConversationHistoryPage,
    IdempotentTurnStatusResponse,
    CoreTurnRequest,
    CoreTurnResponse,
    ProviderModelDiscoveryResponse,
    ProviderCreateRequest,
    ProviderUpdateRequest,
    ProviderSummary,
    ProviderListResponse,
    ProviderDeleteResponse,
    ProviderConnectionTestResponse,
    ProviderChatTestResponse,
    ConversationModelSelectionRequest,
    ConversationModelSelectionResponse,
)
from .lila_core.adapters import ModelProviderConnectionUnavailable
from .lila_core.context import AssembledContext, ContextAssembler
from .lila_core.openai_compatible import (
    ModelProviderConfigurationError,
    ModelProviderUnavailable,
    OpenAICompatibleConfig,
    OpenAICompatibleModelAdapter,
)
from .lila_core.memory import (
    ConversationMessage,
    IdempotencyKeyConflict,
    IdempotentTurnInProgress,
)
from .lila_core.provider_registry import validate_provider_endpoint
from .lila_core.provider_egress import create_provider_http_client
from .lila_core.service import CoreTurnService, ModelAdapterContractError
from .lila_core.provider_routing import (
    OwnerScopedModelAdapterResolver,
    ProviderSelectionRequired,
    SelectedProviderUnavailable,
    create_provider_model_adapter,
)
from .lila_core.sqlite_store import (
    ProviderAlreadyExists,
    ProviderDisabled,
    ProviderLimitExceeded,
    ProviderNotFound,
    SqliteLilaStore,
)
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
    lila_history_reader: Any | None = None,
    lila_provider_adapter: OpenAICompatibleModelAdapter | None = None,
    lila_provider_registry: Any | None = None,
    lila_provider_http_client_factory: Any | None = None,
    lila_store: Any | None = None,
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

        app.state.lila_store = lila_store
        app.state.lila_history_reader = lila_history_reader
        app.state.lila_provider_adapter = lila_provider_adapter
        app.state.lila_provider_registry = lila_provider_registry
        if (
            lila_core_service is None
            and app.state.settings.lila_configuration_requested
            and not app.state.settings.lila_storage_is_configured
            and lila_store is None
        ):
            raise RuntimeError("Lila configuration is incomplete: encrypted storage is not configured")
        if (
            lila_core_service is None
            and app.state.settings.lila_provider_configuration_requested
            and not app.state.settings.lila_provider_is_configured
        ):
            raise RuntimeError("Operator Lila provider configuration is incomplete")
        if lila_core_service is not None:
            app.state.lila_core_service = lila_core_service
        elif app.state.settings.lila_storage_is_configured or lila_store is not None:
            store = lila_store or SqliteLilaStore(
                app.state.settings.lila_database_path,
                app.state.settings.lila_storage_encryption_key,
            )
            await store.initialize()
            app.state.lila_store = store
            app.state.lila_history_reader = lila_history_reader or store
            if app.state.lila_provider_registry is None:
                app.state.lila_provider_registry = store

            default_adapter = lila_provider_adapter
            if app.state.settings.lila_provider_is_configured:
                provider_config = OpenAICompatibleConfig(
                    provider_id=app.state.settings.lila_provider_id,
                    base_url=app.state.settings.lila_provider_base_url,
                    model_id=app.state.settings.lila_provider_model_id,
                    api_key=app.state.settings.lila_provider_api_key,
                    timeout_seconds=app.state.settings.lila_provider_timeout_seconds,
                    system_prompt=app.state.settings.lila_system_prompt,
                )
                default_adapter = OpenAICompatibleModelAdapter(
                    app.state.http_client,
                    provider_config,
                )
            app.state.lila_provider_adapter = default_adapter
            adapter_resolver = OwnerScopedModelAdapterResolver(
                registry=app.state.lila_provider_registry,
                selection_store=store,
                default_adapter=default_adapter,
                system_prompt=app.state.settings.lila_system_prompt,
                http_client_factory=app.state.lila_provider_http_client_factory,
            )
            app.state.lila_core_service = CoreTurnService(
                ContextAssembler(store, store),
                None,
                store,
                model_adapter_resolver=adapter_resolver,
            )
        elif app.state.settings.environment == "production":
            raise RuntimeError(
                "Production requires encrypted persistent Lila storage"
            )
        else:
            app.state.lila_core_service = None
            app.state.lila_provider_adapter = lila_provider_adapter

        yield
        if app.state.redis is not None:
            await app.state.redis.aclose()
        if app.state.owns_http_client:
            await app.state.http_client.aclose()

    app = FastAPI(title="Galaxy Backend", version="0.2.0", lifespan=lifespan)
    app.state.lila_core_service = lila_core_service
    app.state.lila_store = lila_store
    app.state.lila_history_reader = lila_history_reader
    app.state.lila_provider_adapter = lila_provider_adapter
    app.state.lila_provider_registry = lila_provider_registry
    app.state.lila_provider_http_client_factory = (
        lila_provider_http_client_factory or create_provider_http_client
    )

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

    @app.get(
        "/v1/lila/conversations/{conversation_id}/messages",
        response_model=ConversationHistoryPage,
    )
    async def get_lila_conversation_history(
        conversation_id: str,
        request: Request,
        user_id: Annotated[str, Depends(current_user)],
        limit: int = Query(default=20, ge=1, le=20),
        before_sequence: int | None = Query(default=None, ge=1),
    ):
        if not conversation_id.strip() or len(conversation_id) > 128:
            raise HTTPException(status_code=422, detail="Conversation ID is invalid")
        reader = request.app.state.lila_history_reader
        if reader is None:
            raise HTTPException(
                status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
                detail="Lila conversation history is not configured",
            )
        try:
            messages, next_cursor = await reader.load_conversation_page(
                principal_id=user_id,
                conversation_id=conversation_id,
                limit=limit,
                before_sequence=before_sequence,
            )
        except ValueError:
            raise HTTPException(
                status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
                detail="Conversation history request is invalid",
            ) from None
        except Exception:
            raise HTTPException(
                status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
                detail="Lila conversation history is temporarily unavailable",
                headers={"Retry-After": "5"},
            ) from None
        return ConversationHistoryPage(
            conversation_id=conversation_id,
            messages=[message.model_dump() for message in messages],
            next_before_sequence=next_cursor,
        )

    async def require_lila_provider_registry(request: Request, user_id: str):
        registry = request.app.state.lila_provider_registry
        if registry is None:
            raise HTTPException(
                status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
                detail="Lila provider catalog is not configured",
            )
        try:
            await request.app.state.lila_turn_rate_limiter.check(user_id)
        except HTTPException:
            raise
        except Exception:
            raise HTTPException(
                status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
                detail="Lila provider catalog is temporarily unavailable",
                headers={"Retry-After": "5"},
            ) from None
        return registry

    def provider_summary(record: dict[str, object]) -> ProviderSummary:
        return ProviderSummary(
            provider_id=record["provider_id"],
            provider_type=record["provider_type"],
            model_id=record["model_id"],
            capabilities=record["capabilities"],
            timeout_seconds=record["timeout_seconds"],
            enabled=record["enabled"],
            has_api_key=bool(record.get("api_key")),
        )

    def validated_provider_config(
        *,
        provider_id: str,
        provider_type: str,
        base_url: str,
        model_id: str,
        api_key: str | None,
        timeout_seconds: float,
        capabilities: list[str],
        enabled: bool,
        system_prompt: str,
    ) -> dict[str, object]:
        validate_provider_endpoint(provider_type, base_url)
        # Reuse the adapter's strict URL, identifier, credential and timeout
        # validation without making an outbound request.
        OpenAICompatibleConfig(
            provider_id=provider_id,
            base_url=base_url,
            model_id=model_id,
            api_key=api_key,
            timeout_seconds=timeout_seconds,
            system_prompt=system_prompt,
        )
        return {
            "provider_id": provider_id,
            "provider_type": provider_type,
            "base_url": base_url,
            "model_id": model_id,
            "api_key": api_key,
            "timeout_seconds": timeout_seconds,
            "capabilities": capabilities,
            "enabled": enabled,
        }

    @app.get("/v1/lila/providers", response_model=ProviderListResponse)
    async def list_lila_providers(
        request: Request,
        user_id: Annotated[str, Depends(current_user)],
    ):
        registry = await require_lila_provider_registry(request, user_id)
        try:
            records = await registry.list_providers(principal_id=user_id)
            return ProviderListResponse(
                providers=[provider_summary(record) for record in records]
            )
        except Exception:
            raise HTTPException(
                status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
                detail="Lila provider catalog is temporarily unavailable",
                headers={"Retry-After": "5"},
            ) from None

    @app.post("/v1/lila/providers", response_model=ProviderSummary)
    async def create_lila_provider(
        payload: ProviderCreateRequest,
        request: Request,
        user_id: Annotated[str, Depends(current_user)],
    ):
        registry = await require_lila_provider_registry(request, user_id)
        api_key = payload.api_key.get_secret_value() if payload.api_key is not None else None
        try:
            record = validated_provider_config(
                provider_id=payload.provider_id,
                provider_type=payload.provider_type,
                base_url=payload.base_url,
                model_id=payload.model_id,
                api_key=api_key,
                timeout_seconds=payload.timeout_seconds,
                capabilities=list(payload.capabilities),
                enabled=payload.enabled,
                system_prompt=request.app.state.settings.lila_system_prompt,
            )
            await registry.save_provider(
                principal_id=user_id, provider=record, create_only=True
            )
            return provider_summary(record)
        except ProviderAlreadyExists:
            raise HTTPException(
                status_code=status.HTTP_409_CONFLICT,
                detail="A provider with this identifier already exists",
            ) from None
        except ProviderLimitExceeded:
            raise HTTPException(
                status_code=status.HTTP_409_CONFLICT,
                detail="The provider catalog limit has been reached",
            ) from None
        except ValueError:
            raise HTTPException(
                status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
                detail="Lila provider configuration is invalid",
            ) from None
        except HTTPException:
            raise
        except Exception:
            raise HTTPException(
                status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
                detail="Lila provider catalog is temporarily unavailable",
                headers={"Retry-After": "5"},
            ) from None

    @app.put("/v1/lila/providers/{provider_id}", response_model=ProviderSummary)
    async def update_lila_provider(
        provider_id: str,
        payload: ProviderUpdateRequest,
        request: Request,
        user_id: Annotated[str, Depends(current_user)],
    ):
        registry = await require_lila_provider_registry(request, user_id)
        try:
            existing = await registry.get_provider(
                principal_id=user_id, provider_id=provider_id
            )
            if existing is None:
                raise HTTPException(
                    status_code=status.HTTP_404_NOT_FOUND,
                    detail="Lila provider not found",
                )
            if payload.clear_api_key:
                api_key = None
            elif payload.api_key is not None:
                api_key = payload.api_key.get_secret_value()
            else:
                api_key = existing.get("api_key")
            record = validated_provider_config(
                provider_id=provider_id,
                provider_type=payload.provider_type,
                base_url=payload.base_url,
                model_id=payload.model_id,
                api_key=api_key,
                timeout_seconds=payload.timeout_seconds,
                capabilities=list(payload.capabilities),
                enabled=payload.enabled,
                system_prompt=request.app.state.settings.lila_system_prompt,
            )
            await registry.save_provider(
                principal_id=user_id, provider=record, create_only=False
            )
            return provider_summary(record)
        except HTTPException:
            raise
        except ValueError:
            raise HTTPException(
                status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
                detail="Lila provider configuration is invalid",
            ) from None
        except Exception:
            raise HTTPException(
                status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
                detail="Lila provider catalog is temporarily unavailable",
                headers={"Retry-After": "5"},
            ) from None

    @app.delete(
        "/v1/lila/providers/{provider_id}",
        response_model=ProviderDeleteResponse,
    )
    async def delete_lila_provider(
        provider_id: str,
        request: Request,
        user_id: Annotated[str, Depends(current_user)],
    ):
        registry = await require_lila_provider_registry(request, user_id)
        try:
            deleted = await registry.delete_provider(
                principal_id=user_id, provider_id=provider_id
            )
        except ValueError:
            raise HTTPException(
                status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
                detail="Provider identifier is invalid",
            ) from None
        except Exception:
            raise HTTPException(
                status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
                detail="Lila provider catalog is temporarily unavailable",
                headers={"Retry-After": "5"},
            ) from None
        if not deleted:
            raise HTTPException(
                status_code=status.HTTP_404_NOT_FOUND,
                detail="Lila provider not found",
            )
        return ProviderDeleteResponse(deleted=True)


    @app.post(
        "/v1/lila/providers/{provider_id}/probe",
        response_model=ProviderConnectionTestResponse,
    )
    async def probe_lila_registered_provider(
        provider_id: str,
        request: Request,
        user_id: Annotated[str, Depends(current_user)],
    ):
        """Probe the provider-native model catalog without generating text.

        OpenAI-compatible endpoints use GET /models; native local Ollama uses
        GET /api/tags. Neither probe calls a generation endpoint.
        """
        registry = await require_lila_provider_registry(request, user_id)
        try:
            record = await registry.get_provider(
                principal_id=user_id,
                provider_id=provider_id,
            )
        except ValueError:
            raise HTTPException(
                status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
                detail="Provider identifier is invalid",
            ) from None
        except Exception:
            raise HTTPException(
                status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
                detail="Lila provider catalog is temporarily unavailable",
                headers={"Retry-After": "5"},
            ) from None

        if record is None:
            raise HTTPException(
                status_code=status.HTTP_404_NOT_FOUND,
                detail="Lila provider not found",
            )
        if not record.get("enabled"):
            raise HTTPException(
                status_code=status.HTTP_409_CONFLICT,
                detail="Lila provider is disabled",
            )

        try:
            provider_type = str(record["provider_type"])
            validate_provider_endpoint(provider_type, str(record["base_url"]))
            config = OpenAICompatibleConfig(
                provider_id=str(record["provider_id"]),
                base_url=str(record["base_url"]),
                model_id=str(record["model_id"]),
                api_key=record.get("api_key"),
                timeout_seconds=float(record["timeout_seconds"]),
                system_prompt=request.app.state.settings.lila_system_prompt,
            )
        except (KeyError, TypeError, ValueError):
            raise HTTPException(
                status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
                detail="Stored Lila provider configuration is invalid",
            ) from None

        try:
            client = request.app.state.lila_provider_http_client_factory(
                provider_type,
                float(record["timeout_seconds"]),
            )
            async with client:
                adapter = create_provider_model_adapter(client, provider_type, config)
                model_ids = await adapter.discover_models()
        except ModelProviderConfigurationError as exc:
            raise HTTPException(
                status_code=status.HTTP_502_BAD_GATEWAY,
                detail=exc.public_detail,
            ) from None
        except ModelProviderUnavailable:
            raise HTTPException(
                status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
                detail="Lila model endpoint is temporarily unavailable",
                headers={"Retry-After": "5"},
            ) from None
        except ModelAdapterContractError:
            raise HTTPException(
                status_code=status.HTTP_502_BAD_GATEWAY,
                detail="Lila model provider returned an invalid model catalog",
            ) from None
        except Exception:
            raise HTTPException(
                status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
                detail="Lila provider connection test is temporarily unavailable",
                headers={"Retry-After": "5"},
            ) from None

        return ProviderConnectionTestResponse(
            provider_id=config.provider_id,
            configured_model_id=config.model_id,
            connection_reachable=True,
            model_discovery_supported=model_ids is not None,
            configured_model_available=(
                config.model_id in model_ids if model_ids is not None else None
            ),
            model_ids=list(model_ids or ()),
            chat_completion_verified=False,
        )

    @app.post(
        "/v1/lila/providers/{provider_id}/test-chat",
        response_model=ProviderChatTestResponse,
    )
    async def test_lila_provider_chat_completion(
        provider_id: str,
        request: Request,
        user_id: Annotated[str, Depends(current_user)],
    ):
        """Run an explicit minimal chat generation without adding a conversation turn.

        This operation is generative and may consume provider tokens. It is
        user-triggered, rate-limited, and returns no generated text or provider
        diagnostics. Ambiguous post-send failures are never marked retry-safe.
        """
        registry = await require_lila_provider_registry(request, user_id)
        try:
            record = await registry.get_provider(
                principal_id=user_id,
                provider_id=provider_id,
            )
        except ValueError:
            raise HTTPException(
                status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
                detail="Provider identifier is invalid",
            ) from None
        except Exception:
            raise HTTPException(
                status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
                detail="Lila provider catalog is temporarily unavailable",
                headers={"Retry-After": "5"},
            ) from None

        if record is None:
            raise HTTPException(
                status_code=status.HTTP_404_NOT_FOUND,
                detail="Lila provider not found",
            )
        if not record.get("enabled"):
            raise HTTPException(
                status_code=status.HTTP_409_CONFLICT,
                detail="Lila provider is disabled",
            )

        try:
            provider_type = str(record["provider_type"])
            timeout_seconds = float(record["timeout_seconds"])
            config = OpenAICompatibleConfig(
                provider_id=str(record["provider_id"]),
                base_url=str(record["base_url"]),
                model_id=str(record["model_id"]),
                api_key=record.get("api_key"),
                timeout_seconds=timeout_seconds,
                system_prompt=request.app.state.settings.lila_system_prompt,
            )
            validate_provider_endpoint(provider_type, config.base_url)
        except (KeyError, TypeError, ValueError):
            raise HTTPException(
                status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
                detail="Stored Lila provider configuration is invalid",
            ) from None

        context = AssembledContext(
            conversation_id="lila-provider-chat-test",
            messages=(
                ConversationMessage(
                    role="user",
                    content="Reply with exactly: LILA_OK",
                ),
            ),
        )
        try:
            client = request.app.state.lila_provider_http_client_factory(
                provider_type,
                timeout_seconds,
            )
            async with client:
                adapter = create_provider_model_adapter(client, provider_type, config)
                result = await adapter.generate(context)
        except ModelProviderConnectionUnavailable:
            raise HTTPException(
                status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
                detail="Lila provider could not be reached before the test request was sent; retry is safe.",
                headers={
                    "Retry-After": "5",
                    "X-Lila-Retry-Safe": "true",
                },
            ) from None
        except ModelProviderConfigurationError as exc:
            raise HTTPException(
                status_code=status.HTTP_502_BAD_GATEWAY,
                detail=exc.public_detail,
            ) from None
        except ModelProviderUnavailable:
            raise HTTPException(
                status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
                detail="Lila provider test outcome is unavailable; the provider may have received the request.",
                headers={
                    "Retry-After": "5",
                    "X-Lila-Outcome-Unknown": "true",
                },
            ) from None
        except ModelAdapterContractError:
            raise HTTPException(
                status_code=status.HTTP_502_BAD_GATEWAY,
                detail="Lila provider returned an unsupported chat-completion response",
            ) from None
        except Exception:
            raise HTTPException(
                status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
                detail="Lila provider chat test is temporarily unavailable",
                headers={"Retry-After": "5"},
            ) from None

        return ProviderChatTestResponse(
            provider_id=result.provider_id or config.provider_id,
            model_id=result.model_id or config.model_id,
            chat_completion_verified=True,
            response_characters=len(result.text),
        )

    @app.get(
        "/v1/lila/conversations/{conversation_id}/model",
        response_model=ConversationModelSelectionResponse,
    )
    async def get_lila_conversation_model(
        conversation_id: str,
        request: Request,
        user_id: Annotated[str, Depends(current_user)],
    ):
        await require_lila_provider_registry(request, user_id)
        store = request.app.state.lila_store
        if store is None:
            raise HTTPException(
                status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
                detail="Lila conversation model selection is not configured",
            )
        if not conversation_id.strip() or len(conversation_id) > 128:
            raise HTTPException(status_code=422, detail="Conversation ID is invalid")
        try:
            selection = await store.get_conversation_model(
                principal_id=user_id,
                conversation_id=conversation_id,
            )
        except ValueError:
            raise HTTPException(status_code=422, detail="Conversation ID is invalid") from None
        except Exception:
            raise HTTPException(
                status_code=503,
                detail="Lila conversation model selection is temporarily unavailable",
                headers={"Retry-After": "5"},
            ) from None
        if selection is None:
            return ConversationModelSelectionResponse(
                conversation_id=conversation_id,
                selected=False,
            )
        return ConversationModelSelectionResponse(
            conversation_id=conversation_id,
            selected=True,
            provider_id=selection["provider_id"],
            model_id=selection["model_id"],
        )

    @app.put(
        "/v1/lila/conversations/{conversation_id}/model",
        response_model=ConversationModelSelectionResponse,
    )
    async def set_lila_conversation_model(
        conversation_id: str,
        payload: ConversationModelSelectionRequest,
        request: Request,
        user_id: Annotated[str, Depends(current_user)],
    ):
        registry = await require_lila_provider_registry(request, user_id)
        store = request.app.state.lila_store
        if store is None:
            raise HTTPException(
                status_code=503,
                detail="Lila conversation model selection is not configured",
            )
        if not conversation_id.strip() or len(conversation_id) > 128:
            raise HTTPException(status_code=422, detail="Conversation ID is invalid")

        try:
            record = await registry.get_provider(
                principal_id=user_id,
                provider_id=payload.provider_id,
            )
        except ValueError:
            raise HTTPException(status_code=422, detail="Provider identifier is invalid") from None
        except Exception:
            raise HTTPException(
                status_code=503,
                detail="Lila provider catalog is temporarily unavailable",
                headers={"Retry-After": "5"},
            ) from None
        if record is None:
            raise HTTPException(status_code=404, detail="Lila provider not found")
        if not record.get("enabled"):
            raise HTTPException(status_code=409, detail="Lila provider is disabled")

        try:
            provider_type = str(record["provider_type"])
            validate_provider_endpoint(provider_type, str(record["base_url"]))
            config = OpenAICompatibleConfig(
                provider_id=payload.provider_id,
                base_url=str(record["base_url"]),
                model_id=payload.model_id,
                api_key=record.get("api_key"),
                timeout_seconds=float(record["timeout_seconds"]),
                system_prompt=request.app.state.settings.lila_system_prompt,
            )
            client = request.app.state.lila_provider_http_client_factory(
                provider_type,
                float(record["timeout_seconds"]),
            )
            async with client:
                adapter = create_provider_model_adapter(client, provider_type, config)
                discovered_models = await adapter.discover_models()
        except ModelProviderConfigurationError as exc:
            raise HTTPException(status_code=502, detail=exc.public_detail) from None
        except ModelProviderUnavailable:
            raise HTTPException(
                status_code=503,
                detail="Lila model endpoint is temporarily unavailable",
                headers={"Retry-After": "5"},
            ) from None
        except ModelAdapterContractError:
            raise HTTPException(
                status_code=502,
                detail="Lila model provider returned an invalid model catalog",
            ) from None
        except (KeyError, TypeError, ValueError):
            raise HTTPException(status_code=422, detail="Stored Lila provider configuration is invalid") from None
        except Exception:
            raise HTTPException(
                status_code=503,
                detail="Lila model selection is temporarily unavailable",
                headers={"Retry-After": "5"},
            ) from None

        if discovered_models is not None and payload.model_id not in discovered_models:
            raise HTTPException(
                status_code=422,
                detail="The selected model is not available from this provider",
            )
        try:
            await store.set_conversation_model(
                principal_id=user_id,
                conversation_id=conversation_id,
                provider_id=payload.provider_id,
                model_id=payload.model_id,
            )
        except ProviderNotFound:
            raise HTTPException(status_code=404, detail="Lila provider not found") from None
        except ProviderDisabled:
            raise HTTPException(status_code=409, detail="Lila provider is disabled") from None
        except ValueError:
            raise HTTPException(status_code=422, detail="Lila model selection is invalid") from None
        except Exception:
            raise HTTPException(
                status_code=503,
                detail="Lila model selection is temporarily unavailable",
                headers={"Retry-After": "5"},
            ) from None

        return ConversationModelSelectionResponse(
            conversation_id=conversation_id,
            selected=True,
            provider_id=payload.provider_id,
            model_id=payload.model_id,
        )

    @app.post(
        "/v1/lila/provider/probe",
        response_model=ProviderModelDiscoveryResponse,
    )
    async def probe_lila_provider(
        request: Request,
        user_id: Annotated[str, Depends(current_user)],
    ):
        """Probe only the configured /models endpoint; never issue a generation."""
        adapter: OpenAICompatibleModelAdapter | None = request.app.state.lila_provider_adapter
        if adapter is None:
            raise HTTPException(
                status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
                detail="Lila model provider is not configured",
            )

        # Reuse the per-user Lila quota to bound outbound discovery requests.
        try:
            await request.app.state.lila_turn_rate_limiter.check(user_id)
        except HTTPException:
            raise
        except Exception:
            raise HTTPException(
                status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
                detail="Lila provider discovery is temporarily unavailable",
                headers={"Retry-After": "5"},
            ) from None

        try:
            model_ids = await adapter.discover_models()
        except ModelProviderConfigurationError as exc:
            raise HTTPException(
                status_code=status.HTTP_502_BAD_GATEWAY,
                detail=exc.public_detail,
            ) from None
        except ModelProviderUnavailable:
            raise HTTPException(
                status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
                detail="Lila model endpoint is temporarily unavailable",
                headers={"Retry-After": "5"},
            ) from None
        except ModelAdapterContractError:
            raise HTTPException(
                status_code=status.HTTP_502_BAD_GATEWAY,
                detail="Lila model provider returned an invalid model catalog",
            ) from None
        except Exception:
            raise HTTPException(
                status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
                detail="Lila provider discovery is temporarily unavailable",
                headers={"Retry-After": "5"},
            ) from None

        return ProviderModelDiscoveryResponse(
            provider_id=adapter.provider_id,
            configured_model_id=adapter.model_id,
            models_endpoint_responded=True,
            model_discovery_supported=model_ids is not None,
            model_ids=list(model_ids or ()),
            chat_completion_verified=False,
        )

    @app.get(
        "/v1/lila/conversations/{conversation_id}/turns/{idempotency_key}",
        response_model=IdempotentTurnStatusResponse,
    )
    async def get_lila_turn_result(
        conversation_id: str,
        idempotency_key: UUID,
        request: Request,
        user_id: Annotated[str, Depends(current_user)],
    ):
        """Reconcile a possibly lost turn response without invoking the model again."""
        store = request.app.state.lila_store
        if store is None:
            raise HTTPException(
                status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
                detail="Lila turn result storage is not configured",
            )
        if not conversation_id.strip() or len(conversation_id) > 128:
            raise HTTPException(status_code=422, detail="Conversation ID is invalid")
        try:
            await request.app.state.lila_turn_rate_limiter.check(user_id)
        except HTTPException:
            raise
        except Exception:
            raise HTTPException(
                status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
                detail="Lila turn status is temporarily unavailable",
                headers={"Retry-After": "5"},
            ) from None
        try:
            turn_status, response = await store.get_idempotent_turn_result(
                principal_id=user_id,
                conversation_id=conversation_id,
                idempotency_key=str(idempotency_key),
            )
        except ValueError:
            raise HTTPException(status_code=422, detail="Lila turn identifier is invalid") from None
        except Exception:
            raise HTTPException(
                status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
                detail="Lila turn status is temporarily unavailable",
                headers={"Retry-After": "5"},
            ) from None
        if turn_status == "not_found":
            raise HTTPException(status_code=404, detail="Lila turn result not found")
        return IdempotentTurnStatusResponse(
            conversation_id=conversation_id,
            status=turn_status,
            response=response,
        )

    @app.post("/v1/lila/turns", response_model=CoreTurnResponse)
    async def create_lila_turn(
        payload: CoreTurnRequest,
        request: Request,
        user_id: Annotated[str, Depends(current_user)],
        idempotency_key: Annotated[UUID | None, Header(alias="Idempotency-Key")] = None,
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
            if idempotency_key is None:
                return await service.handle(payload, principal_id=user_id)
            return await service.handle(
                payload,
                principal_id=user_id,
                idempotency_key=str(idempotency_key),
            )
        except IdempotencyKeyConflict:
            raise HTTPException(
                status_code=status.HTTP_409_CONFLICT,
                detail="Idempotency-Key was already used for a different request",
            ) from None
        except IdempotentTurnInProgress:
            raise HTTPException(
                status_code=status.HTTP_409_CONFLICT,
                detail="A turn with this Idempotency-Key is still processing; retry the same request.",
                headers={"Retry-After": "2"},
            ) from None
        except ProviderSelectionRequired:
            raise HTTPException(
                status_code=status.HTTP_409_CONFLICT,
                detail="Select a model provider for this conversation before sending a message.",
            ) from None
        except SelectedProviderUnavailable:
            raise HTTPException(
                status_code=status.HTTP_409_CONFLICT,
                detail="The selected provider is unavailable or disabled; choose another provider.",
            ) from None
        except ModelProviderConnectionUnavailable:
            raise HTTPException(
                status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
                detail="Lila model provider could not be reached before generation; retry is safe.",
                headers={"Retry-After": "5", "X-Lila-Retry-Safe": "true"},
            ) from None
        except ModelProviderConfigurationError as exc:
            raise HTTPException(
                status_code=status.HTTP_502_BAD_GATEWAY,
                detail=exc.public_detail,
            ) from None
        except ModelProviderUnavailable:
            raise HTTPException(
                status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
                detail="Lila model provider outcome is unknown; it may have received the request.",
                headers={
                    "Retry-After": "5",
                    "X-Lila-Outcome-Unknown": "true",
                },
            ) from None
        except (ModelAdapterContractError, ValidationError):
            raise HTTPException(
                status_code=status.HTTP_502_BAD_GATEWAY,
                detail="Lila model returned an invalid response",
                headers={"X-Lila-Outcome-Unknown": "true"},
            ) from None
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
