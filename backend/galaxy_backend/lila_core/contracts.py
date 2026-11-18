"""Provider-neutral request/response contracts for Lila Core."""

from __future__ import annotations

from typing import Annotated, Literal
from uuid import UUID, uuid4

from pydantic import BaseModel, ConfigDict, Field, field_validator


Emotion = Literal[
    "neutral",
    "happy",
    "sad",
    "curious",
    "playful",
    "affectionate",
    "serious",
]


class CoreTurnRequest(BaseModel):
    """A single user turn; conversation history is loaded server-side."""

    model_config = ConfigDict(extra="forbid")

    conversation_id: str = Field(min_length=1, max_length=128)
    message: str = Field(min_length=1, max_length=12_000)

    @field_validator("message")
    @classmethod
    def reject_whitespace_only_message(cls, value: str) -> str:
        if not value.strip():
            raise ValueError("message must contain non-whitespace characters")
        return value


class EmotionPayload(BaseModel):
    model_config = ConfigDict(extra="forbid")

    emotion: Emotion
    intensity: float = Field(default=0.5, ge=0.0, le=1.0)


class ActionPayload(BaseModel):
    model_config = ConfigDict(extra="forbid")

    action: str = Field(min_length=1, max_length=64)
    parameters: dict[str, str | int | float | bool] = Field(default_factory=dict)


class AvatarEmotionEvent(BaseModel):
    model_config = ConfigDict(extra="forbid")

    event_id: UUID = Field(default_factory=uuid4)
    type: Literal["avatar.emotion"] = "avatar.emotion"
    version: Literal[1] = 1
    payload: EmotionPayload


class AvatarActionEvent(BaseModel):
    model_config = ConfigDict(extra="forbid")

    event_id: UUID = Field(default_factory=uuid4)
    type: Literal["avatar.action"] = "avatar.action"
    version: Literal[1] = 1
    payload: ActionPayload


AvatarEvent = Annotated[
    AvatarEmotionEvent | AvatarActionEvent,
    Field(discriminator="type"),
]


class CoreTurnResponse(BaseModel):
    """Assistant output plus optional declarative UI/avatar events."""

    model_config = ConfigDict(extra="forbid")

    conversation_id: str = Field(min_length=1, max_length=128)
    text: str
    emotion: Emotion = "neutral"
    avatar_events: list[AvatarEvent] = Field(default_factory=list)
