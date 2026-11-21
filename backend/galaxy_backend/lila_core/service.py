"""Application orchestration for one authenticated Lila Core turn."""

from __future__ import annotations

from collections.abc import Awaitable, Callable

from .adapters import ModelAdapter, ModelAdapterContractError, ModelProviderRejected
from .context import ContextAssembler
from .contracts import CoreTurnRequest, CoreTurnResponse
from .memory import ConversationHistoryWriter


class CoreTurnService:
    """Assemble context, generate a response, then durably commit the complete turn.

    A response is not returned to the client until both the user message and the
    assistant response have been committed by the owner-scoped history writer.
    Authentication and provider execution remain outside the persistence adapter.
    """

    def __init__(
        self,
        context_assembler: ContextAssembler,
        model_adapter: ModelAdapter | None,
        history_writer: ConversationHistoryWriter,
        *,
        model_adapter_resolver: Callable[[str, str], Awaitable[ModelAdapter]] | None = None,
    ) -> None:
        if model_adapter is None and model_adapter_resolver is None:
            raise ValueError("CoreTurnService requires a model adapter or resolver")
        self._context_assembler = context_assembler
        self._model_adapter = model_adapter
        self._model_adapter_resolver = model_adapter_resolver
        self._history_writer = history_writer

    async def handle(
        self,
        request: CoreTurnRequest,
        *,
        principal_id: str,
        idempotency_key: str | None = None,
    ) -> CoreTurnResponse:
        if idempotency_key is None:
            context = await self._context_assembler.assemble(
                request,
                principal_id=principal_id,
            )
            adapter = await self._resolve_model_adapter(principal_id, request.conversation_id)
            response = await self._generate_response(context, adapter)
            await self._history_writer.append_turn(
                principal_id=principal_id,
                conversation_id=request.conversation_id,
                user_message=request.message,
                assistant_message=response.text,
                provider_id=response.provider_id,
                model_id=response.model_id,
            )
            return response

        cached_response = await self._history_writer.reserve_idempotent_turn(
            principal_id=principal_id,
            conversation_id=request.conversation_id,
            idempotency_key=idempotency_key,
            user_message=request.message,
        )
        if cached_response is not None:
            return cached_response

        try:
            context = await self._context_assembler.assemble(
                request,
                principal_id=principal_id,
            )
        except BaseException:
            # No provider call has started, so releasing this reservation is safe.
            try:
                await self._history_writer.release_idempotent_turn(
                    principal_id=principal_id,
                    conversation_id=request.conversation_id,
                    idempotency_key=idempotency_key,
                    user_message=request.message,
                )
            except Exception:
                pass
            raise

        # Resolve configuration before provider execution. Any resolver failure
        # is safe to release; once generation starts, ambiguous failures retain the
        # reservation so a retry cannot trigger a second potentially billable call.
        try:
            adapter = await self._resolve_model_adapter(principal_id, request.conversation_id)
        except BaseException:
            try:
                await self._history_writer.release_idempotent_turn(
                    principal_id=principal_id,
                    conversation_id=request.conversation_id,
                    idempotency_key=idempotency_key,
                    user_message=request.message,
                )
            except Exception:
                pass
            raise

        try:
            response = await self._generate_response(context, adapter)
        except ModelProviderRejected:
            # Authentication/model/endpoint rejection is definitive; after the
            # operator fixes configuration, the same key can safely be retried.
            try:
                await self._history_writer.release_idempotent_turn(
                    principal_id=principal_id,
                    conversation_id=request.conversation_id,
                    idempotency_key=idempotency_key,
                    user_message=request.message,
                )
            except Exception:
                pass
            raise
        await self._history_writer.complete_idempotent_turn(
            principal_id=principal_id,
            conversation_id=request.conversation_id,
            idempotency_key=idempotency_key,
            user_message=request.message,
            assistant_message=response.text,
            response=response,
        )
        return response

    async def _resolve_model_adapter(
        self, principal_id: str, conversation_id: str
    ) -> ModelAdapter:
        if self._model_adapter_resolver is not None:
            return await self._model_adapter_resolver(principal_id, conversation_id)
        if self._model_adapter is None:
            raise ModelAdapterContractError("No Lila model adapter is configured")
        return self._model_adapter

    async def _generate_response(
        self, context, adapter: ModelAdapter
    ) -> CoreTurnResponse:
        raw_response = await adapter.generate(context)
        response = CoreTurnResponse.model_validate(raw_response)
        if response.conversation_id != context.conversation_id:
            raise ModelAdapterContractError(
                "The model adapter returned a response for a different conversation."
            )
        return response
