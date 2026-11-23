"""Owner-scoped model adapter resolution with explicitly configured failover."""
from __future__ import annotations

from typing import Any, Callable
from urllib.parse import urlsplit

import httpx

from .adapters import (
    ModelAdapter,
    ModelProviderConnectionUnavailable,
    ModelProviderRejected,
)
from .context import AssembledContext
from .contracts import CoreTurnResponse
from .openai_compatible import (
    ModelProviderConfigurationError,
    OpenAICompatibleConfig,
    OpenAICompatibleModelAdapter,
)
from .provider_registry import validate_provider_endpoint
from .ollama import OllamaModelAdapter


class ProviderSelectionRequired(ModelProviderRejected):
    """The conversation needs an explicit provider/model selection."""


class SelectedProviderUnavailable(ModelProviderRejected):
    """The selected owner provider was removed, disabled, or no longer supports chat."""


class SelectedFallbackProviderUnavailable(ModelProviderRejected):
    """The explicitly configured fallback is unavailable after a safe primary failure."""


def is_native_ollama_endpoint(provider_type: str, base_url: str) -> bool:
    """Select Ollama's native API only for its local root endpoint.

    A local Ollama endpoint ending in /v1 deliberately stays on the common
    OpenAI-compatible adapter. This allows both supported Ollama API surfaces
    without guessing from the provider name or silently changing protocols.
    """
    if provider_type != "local":
        return False
    try:
        parsed = urlsplit(base_url)
        port = parsed.port
    except ValueError:
        return False
    return port == 11434 and parsed.path in {"", "/"}


def create_provider_model_adapter(
    http_client: httpx.AsyncClient,
    provider_type: str,
    config: OpenAICompatibleConfig,
) -> OpenAICompatibleModelAdapter | OllamaModelAdapter:
    """Build the explicit runtime adapter for a validated provider endpoint."""
    if is_native_ollama_endpoint(provider_type, config.base_url):
        return OllamaModelAdapter(http_client, config)
    return OpenAICompatibleModelAdapter(http_client, config)


class ManagedProviderModelAdapter:
    """Open one short-lived pinned client for exactly one model generation."""

    def __init__(
        self,
        *,
        provider_type: str,
        timeout_seconds: float,
        config: OpenAICompatibleConfig,
        http_client_factory: Callable[[str, float], httpx.AsyncClient],
    ) -> None:
        self._provider_type = provider_type
        self._timeout_seconds = timeout_seconds
        self._config = config
        self._http_client_factory = http_client_factory

    async def generate(self, context: AssembledContext):
        client = self._http_client_factory(
            self._provider_type,
            self._timeout_seconds,
        )
        async with client:
            adapter = create_provider_model_adapter(
                client,
                self._provider_type,
                self._config,
            )
            return await adapter.generate(context)


class ExplicitFallbackModelAdapter:
    """Use a configured fallback only when primary failure is known to be pre-send.

    HTTP errors, read/write failures, timeouts after connection, and malformed
    responses are deliberately not retried through another model because the
    primary provider may already have received or billed the generation.
    """

    def __init__(
        self,
        *,
        primary: ModelAdapter | None,
        fallback: ModelAdapter | None,
        primary_provider_id: str,
        primary_model_id: str,
        fallback_provider_id: str,
        fallback_model_id: str,
        primary_failure_reason: str | None = None,
    ) -> None:
        self._primary = primary
        self._fallback = fallback
        self._primary_provider_id = primary_provider_id
        self._primary_model_id = primary_model_id
        self._fallback_provider_id = fallback_provider_id
        self._fallback_model_id = fallback_model_id
        self._primary_failure_reason = primary_failure_reason

    async def generate(self, context: AssembledContext) -> CoreTurnResponse:
        if self._primary is None:
            if self._fallback is None:
                raise SelectedFallbackProviderUnavailable(
                    "The configured fallback provider is unavailable"
                )
            return await self._generate_fallback(
                context,
                self._primary_failure_reason or "primary_provider_unavailable",
            )

        try:
            return await self._primary.generate(context)
        except ModelProviderConnectionUnavailable:
            if self._fallback is None:
                raise SelectedFallbackProviderUnavailable(
                    "The configured fallback provider is unavailable"
                ) from None
            return await self._generate_fallback(
                context,
                "primary_connection_unavailable",
            )

    async def _generate_fallback(
        self,
        context: AssembledContext,
        reason: str,
    ) -> CoreTurnResponse:
        if self._fallback is None:
            raise SelectedFallbackProviderUnavailable(
                "The configured fallback provider is unavailable"
            )
        response = await self._fallback.generate(context)
        return response.model_copy(update={
            "provider_id": self._fallback_provider_id,
            "model_id": self._fallback_model_id,
            "fallback_used": True,
            "fallback_from_provider_id": self._primary_provider_id,
            "fallback_from_model_id": self._primary_model_id,
            "fallback_reason": reason,
        })


class OwnerScopedModelAdapterResolver:
    """Resolve owner-selected primary and optional fallback adapters.

    The operator provider remains a legacy default only when the owner has no
    managed providers and no explicit conversation selection.
    """

    def __init__(
        self,
        *,
        registry: Any,
        selection_store: Any,
        default_adapter: ModelAdapter | None,
        system_prompt: str,
        http_client_factory: Callable[[str, float], httpx.AsyncClient],
    ) -> None:
        self._registry = registry
        self._selection_store = selection_store
        self._default_adapter = default_adapter
        self._system_prompt = system_prompt
        self._http_client_factory = http_client_factory

    async def _managed_adapter(
        self,
        principal_id: str,
        provider_id: str,
        model_id: str,
        *,
        optional: bool,
    ) -> ModelAdapter | None:
        record = await self._registry.get_provider(
            principal_id=principal_id,
            provider_id=provider_id,
        )
        if (
            record is None
            or not record.get("enabled")
            or "chat" not in record.get("capabilities", [])
        ):
            return None
        try:
            provider_type = str(record["provider_type"])
            base_url = str(record["base_url"])
            timeout_seconds = float(record["timeout_seconds"])
            validate_provider_endpoint(provider_type, base_url)
            config = OpenAICompatibleConfig(
                provider_id=provider_id,
                base_url=base_url,
                model_id=model_id,
                api_key=record.get("api_key"),
                timeout_seconds=timeout_seconds,
                system_prompt=self._system_prompt,
            )
        except (KeyError, TypeError, ValueError):
            if optional:
                return None
            raise ModelProviderConfigurationError("request_rejected") from None

        return ManagedProviderModelAdapter(
            provider_type=provider_type,
            timeout_seconds=timeout_seconds,
            config=config,
            http_client_factory=self._http_client_factory,
        )

    async def __call__(self, principal_id: str, conversation_id: str) -> ModelAdapter:
        selection = await self._selection_store.get_conversation_model(
            principal_id=principal_id,
            conversation_id=conversation_id,
        )
        if selection is None:
            providers = await self._registry.list_providers(principal_id=principal_id)
            if providers:
                raise ProviderSelectionRequired(
                    "A provider must be selected for this conversation"
                )
            if self._default_adapter is None:
                raise ProviderSelectionRequired(
                    "No model provider is configured for this conversation"
                )
            return self._default_adapter

        provider_id = selection["provider_id"]
        model_id = selection["model_id"]
        fallback_provider_id = selection.get("fallback_provider_id")
        fallback_model_id = selection.get("fallback_model_id")
        if (fallback_provider_id is None) != (fallback_model_id is None):
            raise ModelProviderConfigurationError("request_rejected")
        if fallback_provider_id == provider_id and fallback_provider_id is not None:
            raise ModelProviderConfigurationError("request_rejected")

        primary_adapter = await self._managed_adapter(
            principal_id, provider_id, model_id, optional=False
        )
        fallback_adapter = None
        if fallback_provider_id is not None and fallback_model_id is not None:
            fallback_adapter = await self._managed_adapter(
                principal_id,
                fallback_provider_id,
                fallback_model_id,
                optional=True,
            )

        if primary_adapter is None:
            if fallback_provider_id is None or fallback_model_id is None:
                raise SelectedProviderUnavailable(
                    "The selected model provider is unavailable"
                )
            if fallback_adapter is None:
                raise SelectedFallbackProviderUnavailable(
                    "The configured fallback provider is unavailable"
                )
            return ExplicitFallbackModelAdapter(
                primary=None,
                fallback=fallback_adapter,
                primary_provider_id=provider_id,
                primary_model_id=model_id,
                fallback_provider_id=fallback_provider_id,
                fallback_model_id=fallback_model_id,
                primary_failure_reason="primary_provider_unavailable",
            )

        if fallback_provider_id is None or fallback_model_id is None:
            return primary_adapter

        return ExplicitFallbackModelAdapter(
            primary=primary_adapter,
            fallback=fallback_adapter,
            primary_provider_id=provider_id,
            primary_model_id=model_id,
            fallback_provider_id=fallback_provider_id,
            fallback_model_id=fallback_model_id,
        )
