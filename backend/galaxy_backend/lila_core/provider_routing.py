"""Owner-scoped model routing for Lila Core.

The router resolves a conversation's explicit model selection only when the
Core service actually needs to generate. This keeps completed idempotent turns
replayable even if a provider is later edited, disabled, or removed.
"""
from __future__ import annotations

from typing import Any, Callable

import httpx

from .adapters import ModelAdapter, ModelAdapterContractError, ModelProviderRejected
from .context import AssembledContext
from .openai_compatible import (
    ModelProviderConfigurationError,
    OpenAICompatibleConfig,
    OpenAICompatibleModelAdapter,
)
from .provider_registry import validate_provider_endpoint


class ProviderSelectionRequired(ModelProviderRejected):
    """The conversation has no explicit model selection while owner providers exist."""


class SelectedProviderUnavailable(ModelProviderRejected):
    """The selected owner provider was removed, disabled, or no longer supports chat."""


class OwnerScopedModelRouter:
    """Resolve one authenticated owner's saved provider/model for each new generation.

    A selected provider failure is surfaced to the user. This router never
    silently switches to another model. The legacy operator provider is used
    only when the owner has no catalog entries and has not selected a model.
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

    async def generate(self, context: AssembledContext, *, principal_id: str):
        selection = await self._selection_store.get_conversation_model(
            principal_id=principal_id,
            conversation_id=context.conversation_id,
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
            return await self._default_adapter.generate(context)

        provider_id = selection["provider_id"]
        model_id = selection["model_id"]
        record = await self._registry.get_provider(
            principal_id=principal_id,
            provider_id=provider_id,
        )
        if record is None or not record.get("enabled") or "chat" not in record.get("capabilities", []):
            raise SelectedProviderUnavailable(
                "The selected model provider is unavailable"
            )

        try:
            provider_type = str(record["provider_type"])
            base_url = str(record["base_url"])
            validate_provider_endpoint(provider_type, base_url)
            config = OpenAICompatibleConfig(
                provider_id=provider_id,
                base_url=base_url,
                model_id=model_id,
                api_key=record.get("api_key"),
                timeout_seconds=float(record["timeout_seconds"]),
                system_prompt=self._system_prompt,
            )
        except (KeyError, TypeError, ValueError):
            raise ModelProviderConfigurationError("request_rejected") from None

        client = self._http_client_factory(
            provider_type,
            float(record["timeout_seconds"]),
        )
        async with client:
            adapter = OpenAICompatibleModelAdapter(client, config)
            response = await adapter.generate(context)

        if response.provider_id != provider_id or response.model_id != model_id:
            raise ModelAdapterContractError(
                "The selected model adapter returned an unexpected provider identity"
            )
        return response
