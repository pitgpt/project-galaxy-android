import asyncio

from galaxy_backend.lila_core.contracts import CoreTurnRequest
from galaxy_backend.lila_core.fake_adapter import FakeModelAdapter


def test_fake_adapter_returns_deterministic_response_for_same_conversation():
    adapter = FakeModelAdapter(
        response_text="Рада тебя видеть.",
        emotion="happy",
    )
    request = CoreTurnRequest(
        conversation_id="conversation-42",
        message="Привет, Лила!",
    )

    response = asyncio.run(adapter.generate(request))

    assert response.conversation_id == "conversation-42"
    assert response.text == "Рада тебя видеть."
    assert response.emotion == "happy"
    assert response.avatar_events == []


def test_fake_adapter_defaults_to_neutral_emotion():
    adapter = FakeModelAdapter()
    request = CoreTurnRequest(conversation_id="conversation-7", message="Привет")

    response = asyncio.run(adapter.generate(request))

    assert response.emotion == "neutral"
    assert response.conversation_id == request.conversation_id
