"""Owner-scoped model adapter resolution for Lila Core.

Selection is resolved after CoreTurnService has checked the idempotency cache.
A selected provider is never silently replaced by another provider.
"""
from __future__ import annotations

from typing import Any, Callable

import httpx

from .adapters import ModelAdapter, ModelProviderRejected
from .context import AssembledContext
from .openai_compatible import (
    ModelProviderConfigurationError,
    OpenAICompatibleConfig,
    OpenAICompatibleModelAdapter,
)
from .provider_registry import validate_provider_endpoint


class ProviderSelectionRequired(ModelProviderRejected):
    """The conversation needs an explicit provider/model selection."""


class SelectedProviderUnavailable(ModelProviderRejected):
    """The selected owner provider was removed, disabled, or no longer supports chat."""


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


class OwnerScopedModelAdapterResolver:
    """Resolve the authenticated owner's conversation selection to a model adapter.

    The resolver is called only after idempotency replay lookup. The operator
    provider is retained solely as the legacy default when the owner has no
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
        record = await self._registry.get_provider(
            principal_id=principal_id,
            provider_id=provider_id,
        )
        if (
            record is None
            or not record.get("enabled")
            or "chat" not in record.get("capabilities", [])
        ):
            raise SelectedProviderUnavailable(
                "The selected model provider is unavailable"
            )

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
            raise ModelProviderConfigurationError("request_rejected") from None

        return ManagedOpenAICompatibleModelAdapter(
            provider_type=provider_type,
            timeout_seconds=timeout_seconds,
            config=config,
            http_client_factory=self._http_client_factory,
        )
