import asyncio

import pytest
from pydantic import ValidationError

from galaxy_backend.lila_core.context import AssembledContext, ContextAssembler
from galaxy_backend.lila_core.contracts import CoreTurnRequest, CoreTurnResponse
from galaxy_backend.lila_core.memory import ConversationMessage, MemorySnippet
from galaxy_backend.lila_core.adapters import (
    ModelProviderConnectionUnavailable,
    ModelProviderRejected,
)
from galaxy_backend.lila_core.service import CoreTurnService, ModelAdapterContractError


class FakeHistoryReader:
    def __init__(self, messages=(), error=None, append_error=None):
        self.messages = list(messages)
        self.error = error
        self.append_error = append_error
        self.calls = []
        self.appended_turns = []
        self.model_identities = []
        self.reservations = set()

    async def reserve_idempotent_turn(
        self, *, principal_id, conversation_id, idempotency_key, user_message
    ):
        key = (principal_id, conversation_id, idempotency_key)
        if key in self.reservations:
            raise RuntimeError("duplicate reservation")
        self.reservations.add(key)
        return None

    async def complete_idempotent_turn(
        self, *, principal_id, conversation_id, idempotency_key,
        user_message, assistant_message, response
    ):
        self.reservations.discard((principal_id, conversation_id, idempotency_key))
        await self.append_turn(
            principal_id=principal_id,
            conversation_id=conversation_id,
            user_message=user_message,
            assistant_message=assistant_message,
            provider_id=response.provider_id,
            model_id=response.model_id,
        )

    async def release_idempotent_turn(
        self, *, principal_id, conversation_id, idempotency_key, user_message
    ):
        self.reservations.discard((principal_id, conversation_id, idempotency_key))

    async def load_recent_messages(self, *, principal_id, conversation_id, limit):
        self.calls.append((principal_id, conversation_id, limit))
        if self.error is not None:
            raise self.error
        return self.messages

    async def append_turn(
        self, *, principal_id, conversation_id, user_message, assistant_message,
        provider_id=None, model_id=None
    ):
        self.appended_turns.append((principal_id, conversation_id, user_message, assistant_message))
        self.model_identities.append((provider_id, model_id))
        if self.append_error is not None:
            raise self.append_error


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
    return CoreTurnService(assembler, adapter, history), history, memories, adapter


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
    assert history.appended_turns == [
        ("jwt-subject-1", "conversation-1", "Продолжим разговор?", "Ответ Лилы")
    ]
    assert history.calls == [("jwt-subject-1", "conversation-1", 20)]
    assert history.model_identities == [(None, None)]
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
async def test_service_persists_provider_and_model_identity_with_the_assistant_turn():
    adapter = FakeModelAdapter(response=CoreTurnResponse(
        conversation_id="conversation-1",
        text="Ответ от выбранной модели",
        provider_id="cloud-primary",
        model_id="model-a",
    ))
    service, history, _, _ = build_service(adapter=adapter)

    await service.handle(
        CoreTurnRequest(conversation_id="conversation-1", message="Проверь модель"),
        principal_id="jwt-subject-1",
    )

    assert history.model_identities == [("cloud-primary", "model-a")]


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


@pytest.mark.asyncio
async def test_persistence_failure_prevents_uncommitted_response_from_returning():
    history = FakeHistoryReader(append_error=RuntimeError("history write failed"))
    service, _, _, adapter = build_service(history=history)

    with pytest.raises(RuntimeError, match="history write failed"):
        await service.handle(
            CoreTurnRequest(conversation_id="conversation-1", message="Привет"),
            principal_id="user-1",
        )

    assert len(adapter.contexts) == 1
    assert history.appended_turns == [
        ("user-1", "conversation-1", "Привет", "Ответ Лилы")
    ]



@pytest.mark.asyncio
async def test_definitive_provider_rejection_releases_idempotency_reservation():
    history = FakeHistoryReader()
    service, _, _, _ = build_service(
        history=history,
        adapter=FakeModelAdapter(error=ModelProviderRejected("provider rejected request")),
    )

    with pytest.raises(ModelProviderRejected):
        await service.handle(
            CoreTurnRequest(conversation_id="conversation-1", message="Привет"),
            principal_id="user-1",
            idempotency_key="00000000-0000-4000-8000-000000000126",
        )

    assert history.reservations == set()


@pytest.mark.asyncio
async def test_pre_send_connection_failure_releases_reservation_and_allows_safe_retry():
    history = FakeHistoryReader()

    class ConnectThenSuccessAdapter:
        def __init__(self):
            self.calls = 0

        async def generate(self, context):
            self.calls += 1
            if self.calls == 1:
                raise ModelProviderConnectionUnavailable("provider could not be reached")
            return CoreTurnResponse(
                conversation_id=context.conversation_id,
                text="Ответ после восстановления соединения",
                provider_id="cloud-primary",
                model_id="model-a",
            )

    adapter = ConnectThenSuccessAdapter()
    service, _, _, _ = build_service(history=history, adapter=adapter)
    request = CoreTurnRequest(conversation_id="conversation-1", message="Привет")
    key = "00000000-0000-4000-8000-000000000128"

    with pytest.raises(ModelProviderConnectionUnavailable):
        await service.handle(request, principal_id="user-1", idempotency_key=key)

    assert history.reservations == set()

    response = await service.handle(request, principal_id="user-1", idempotency_key=key)

    assert response.text == "Ответ после восстановления соединения"
    assert adapter.calls == 2
    assert history.reservations == set()
    assert history.model_identities == [("cloud-primary", "model-a")]


@pytest.mark.asyncio
async def test_ambiguous_provider_failure_keeps_idempotency_reservation():
    history = FakeHistoryReader()
    service, _, _, _ = build_service(
        history=history,
        adapter=FakeModelAdapter(error=RuntimeError("provider outcome unknown")),
    )

    with pytest.raises(RuntimeError):
        await service.handle(
            CoreTurnRequest(conversation_id="conversation-1", message="Привет"),
            principal_id="user-1",
            idempotency_key="00000000-0000-4000-8000-000000000127",
        )

    assert history.reservations == {
        ("user-1", "conversation-1", "00000000-0000-4000-8000-000000000127")
    }
