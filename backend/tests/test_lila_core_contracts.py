import pytest
from pydantic import ValidationError

from galaxy_backend.lila_core.contracts import (
    AvatarActionEvent,
    AvatarEmotionEvent,
    CoreTurnRequest,
    CoreTurnResponse,
)


def test_turn_request_accepts_valid_message():
    request = CoreTurnRequest(conversation_id="conv-1", message="Привет, Лила")
    assert request.conversation_id == "conv-1"


def test_turn_request_rejects_blank_message():
    with pytest.raises(ValidationError):
        CoreTurnRequest(conversation_id="conv-1", message=" ")


def test_emotion_event_is_versioned_and_bounded():
    event = AvatarEmotionEvent(
        payload={"emotion": "happy", "intensity": 0.8}
    )
    assert event.type == "avatar.emotion"
    assert event.version == 1


def test_emotion_event_rejects_unknown_emotion():
    with pytest.raises(ValidationError):
        AvatarEmotionEvent(payload={"emotion": "ecstatic", "intensity": 1.0})


def test_action_event_has_typed_envelope():
    event = AvatarActionEvent(payload={"action": "smile"})
    assert event.payload.action == "smile"


def test_response_defaults_to_neutral_without_events():
    response = CoreTurnResponse(conversation_id="conv-1", text="Здравствуйте.")
    assert response.emotion == "neutral"
    assert response.avatar_events == []
