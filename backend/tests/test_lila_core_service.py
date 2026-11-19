import asyncio

import pytest
from pydantic import ValidationError

from galaxy_backend.lila_core.context import AssembledContext, ContextAssembler
from galaxy_backend.lila_core.contracts import CoreTurnRequest, CoreTurnResponse
from galaxy_backend.lila_core.memory import ConversationMessage, MemorySnippet
from galaxy_backend.lila_core.service import CoreTurnService, ModelAdapterContractError


class FakeHistoryReader:
    def __init__(self, messages=(), error=None):
        self.messages = list(messages)
        self.error = error
        self.calls = []

    async def load_recent_messages(self, *, principal_id, conversation_id, limit):
        self.calls.append((principal_id, conversation_id, limit))
        if self.error is not None:
            raise self.error
        return self.messages


class FakeMemoryReader:
    def __init__(self, memories=()):
        self.memories = list(memories)
        self.calls = []

    async def search_relevant_memories(self, *, principal_id, query, limit):
        self.calls.append((principal_id, query, limit))
        return self.memories


class FakeModelAdapter:
    def __init__(self, response=None, error=None):
        self.response = response
        self.error = error
        self.contexts = []

    async def generate(self, context: AssembledContext):
        self.contexts.append(context)
        if self.error is not None:
            raise self.error
        if self.response is not None:
            return self.response
        return CoreTurnResponse(
            conversation_id=context.conversation_id,
            text="Ответ Лилы",
        )


def build_service(*, history=None, memories=None, adapter=None):
    history = history or FakeHistoryReader(
        [ConversationMessage(role="assistant", content="Предыдущее сообщение")]
    )
    memories = memories or FakeMemoryReader(
        [MemorySnippet(memory_id="memory-1", content="Важное воспоминание")]
    )
    adapter = adapter or FakeModelAdapter()
    assembler = ContextAssembler(history, memories)
    return CoreTurnService(assembler, adapter), history, memories, adapter


@pytest.mark.asyncio
async def test_service_passes_authenticated_bounded_context_to_adapter():
    service, history, memories, adapter = build_service()
    request = CoreTurnRequest(
        conversation_id="conversation-1",
        message="Продолжим разговор?",
    )

    response = await service.handle(request, principal_id="jwt-subject-1")

    assert response.conversation_id == request.conversation_id
    assert response.text == "Ответ Лилы"
    assert history.calls == [("jwt-subject-1", "conversation-1", 20)]
    assert memories.calls == [("jwt-subject-1", "Продолжим разговор?", 8)]
    assert len(adapter.contexts) == 1
    context = adapter.contexts[0]
    assert isinstance(context, AssembledContext)
    assert [message.content for message in context.messages] == [
        "Предыдущее сообщение",
        "Продолжим разговор?",
    ]
    assert [memory.content for memory in context.relevant_memories] == [
        "Важное воспоминание"
    ]


@pytest.mark.asyncio
async def test_service_rejects_adapter_response_for_another_conversation():
    adapter = FakeModelAdapter(
        response=CoreTurnResponse(
            conversation_id="another-conversation",
            text="Несвязанный ответ",
        )
    )
    service, _, _, _ = build_service(adapter=adapter)

    with pytest.raises(ModelAdapterContractError, match="different conversation"):
        await service.handle(
            CoreTurnRequest(conversation_id="conversation-1", message="Привет"),
            principal_id="user-1",
        )


@pytest.mark.asyncio
async def test_history_failure_prevents_model_invocation():
    history_error = RuntimeError("history unavailable")
    history = FakeHistoryReader(error=history_error)
    service, _, _, adapter = build_service(history=history)

    with pytest.raises(RuntimeError, match="history unavailable"):
        await service.handle(
            CoreTurnRequest(conversation_id="conversation-1", message="Привет"),
            principal_id="user-1",
        )

    assert adapter.contexts == []


@pytest.mark.asyncio
async def test_provider_failure_is_propagated_without_wrapping():
    failure = RuntimeError("provider unavailable")
    service, _, _, _ = build_service(adapter=FakeModelAdapter(error=failure))

    with pytest.raises(RuntimeError) as captured:
        await service.handle(
            CoreTurnRequest(conversation_id="conversation-1", message="Привет"),
            principal_id="user-1",
        )

    assert captured.value is failure


@pytest.mark.asyncio
async def test_cancellation_is_not_swallowed():
    service, _, _, _ = build_service(
        adapter=FakeModelAdapter(error=asyncio.CancelledError())
    )

    with pytest.raises(asyncio.CancelledError):
        await service.handle(
            CoreTurnRequest(conversation_id="conversation-1", message="Привет"),
            principal_id="user-1",
        )


@pytest.mark.asyncio
async def test_adapter_output_is_validated_before_return():
    adapter = FakeModelAdapter(
        response={
            "conversation_id": "conversation-1",
            "text": "Ответ",
            "unexpected": True,
        }
    )
    service, _, _, _ = build_service(adapter=adapter)

    with pytest.raises(ValidationError):
        await service.handle(
            CoreTurnRequest(conversation_id="conversation-1", message="Привет"),
            principal_id="user-1",
        )
