"""Application orchestration for one authenticated Lila Core turn."""

from __future__ import annotations

from .adapters import ModelAdapter
from .context import ContextAssembler
from .contracts import CoreTurnRequest, CoreTurnResponse


class ModelAdapterContractError(RuntimeError):
    """Raised when a provider returns a response for a different conversation."""


class CoreTurnService:
    """Assemble owner-scoped context and delegate generation to a model adapter.

    This service is deliberately stateless: persistence, authentication, and
    execution of returned avatar events remain outside the model adapter.
    """

    def __init__(
        self,
        context_assembler: ContextAssembler,
        model_adapter: ModelAdapter,
    ) -> None:
        self._context_assembler = context_assembler
        self._model_adapter = model_adapter

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
        return response
