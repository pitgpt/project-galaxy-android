import asyncio

from galaxy_backend.lila_core.context import AssembledContext
from galaxy_backend.lila_core.fake_adapter import FakeModelAdapter
from galaxy_backend.lila_core.memory import ConversationMessage, MemorySnippet


def test_fake_adapter_returns_deterministic_response_for_assembled_context():
    adapter = FakeModelAdapter(
        response_text="Рада тебя видеть.",
        emotion="happy",
    )
    context = AssembledContext(
        conversation_id="conversation-42",
        messages=(ConversationMessage(role="user", content="Привет, Лила!"),),
        relevant_memories=(
            MemorySnippet(
                memory_id="memory-1",
                content="Пользователь предпочитает русский язык.",
            ),
        ),
    )

    response = asyncio.run(adapter.generate(context))

    assert response.conversation_id == "conversation-42"
    assert response.text == "Рада тебя видеть."
    assert response.emotion == "happy"
    assert response.avatar_events == []


def test_fake_adapter_defaults_to_neutral_emotion():
    adapter = FakeModelAdapter()
    context = AssembledContext(
        conversation_id="conversation-7",
        messages=(ConversationMessage(role="user", content="Привет"),),
    )

    response = asyncio.run(adapter.generate(context))

    assert response.emotion == "neutral"
    assert response.conversation_id == context.conversation_id
