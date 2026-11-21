"""Application orchestration for one authenticated Lila Core turn."""

from __future__ import annotations

from .adapters import ModelAdapter
from .context import ContextAssembler
from .contracts import CoreTurnRequest, CoreTurnResponse
from .memory import ConversationHistoryWriter


class ModelAdapterContractError(RuntimeError):
    """Raised when a provider returns a response for a different conversation."""


class CoreTurnService:
    """Assemble context, generate a response, then durably commit the complete turn.

    A response is not returned to the client until both the user message and the
    assistant response have been committed by the owner-scoped history writer.
    Authentication and provider execution remain outside the persistence adapter.
    """

    def __init__(
        self,
        context_assembler: ContextAssembler,
        model_adapter: ModelAdapter,
        history_writer: ConversationHistoryWriter,
    ) -> None:
        self._context_assembler = context_assembler
        self._model_adapter = model_adapter
        self._history_writer = history_writer

    async def handle(
        self,
        request: CoreTurnRequest,
        *,
        principal_id: str,
    ) -> CoreTurnResponse:
        context = await self._context_assembler.assemble(
            request,
            principal_id=principal_id,
        )
        raw_response = await self._model_adapter.generate(context)
        response = CoreTurnResponse.model_validate(raw_response)
        if response.conversation_id != context.conversation_id:
            raise ModelAdapterContractError(
                "The model adapter returned a response for a different conversation."
            )
        await self._history_writer.append_turn(
            principal_id=principal_id,
            conversation_id=request.conversation_id,
            user_message=request.message,
            assistant_message=response.text,
        )
        return response
