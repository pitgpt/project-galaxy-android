"""Provider-neutral request/response contracts for Lila Core."""

from __future__ import annotations

from typing import Annotated, Literal
from uuid import UUID, uuid4

from pydantic import BaseModel, ConfigDict, Field, SecretStr, field_validator, model_validator


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
    """Assistant output plus non-secret model identity and declarative UI events."""

    model_config = ConfigDict(extra="forbid")

    conversation_id: str = Field(min_length=1, max_length=128)
    text: str = Field(min_length=1, max_length=12_000)
    emotion: Emotion = "neutral"
    avatar_events: list[AvatarEvent] = Field(default_factory=list)
    provider_id: str | None = Field(default=None, min_length=1, max_length=64)
    model_id: str | None = Field(default=None, min_length=1, max_length=256)



class ConversationHistoryItem(BaseModel):
    """One decrypted message returned by the authenticated history endpoint."""

    model_config = ConfigDict(extra="forbid")

    sequence: int = Field(ge=1)
    role: Literal["user", "assistant"]
    content: str = Field(min_length=1, max_length=12_000)
    created_at_epoch_millis: int = Field(ge=0)
    provider_id: str | None = Field(default=None, min_length=1, max_length=64)
    model_id: str | None = Field(default=None, min_length=1, max_length=256)


class ConversationHistoryPage(BaseModel):
    """A bounded, owner-scoped page of committed conversation messages."""

    model_config = ConfigDict(extra="forbid")

    conversation_id: str = Field(min_length=1, max_length=128)
    messages: list[ConversationHistoryItem] = Field(default_factory=list, max_length=20)
    next_before_sequence: int | None = Field(default=None, ge=1)



class ProviderModelDiscoveryResponse(BaseModel):
    """Non-secret result of probing an OpenAI-compatible /models endpoint."""

    model_config = ConfigDict(extra="forbid")

    provider_id: str = Field(min_length=1, max_length=64)
    configured_model_id: str = Field(min_length=1, max_length=256)
    models_endpoint_responded: bool
    model_discovery_supported: bool
    model_ids: list[str] = Field(default_factory=list, max_length=100)
    chat_completion_verified: Literal[False] = False



class ProviderCreateRequest(BaseModel):
    """Create an owner-scoped provider without returning its endpoint or secret."""

    model_config = ConfigDict(extra="forbid")

    provider_id: str = Field(min_length=1, max_length=64)
    provider_type: Literal["cloud", "local"]
    base_url: str = Field(min_length=1, max_length=2048)
    model_id: str = Field(min_length=1, max_length=256)
    api_key: SecretStr | None = Field(default=None, repr=False)
    timeout_seconds: float = Field(default=30.0, ge=1.0, le=120.0)
    capabilities: list[Literal["chat"]] = Field(default_factory=lambda: ["chat"], min_length=1, max_length=1)
    enabled: bool = True

    @field_validator("provider_id")
    @classmethod
    def validate_provider_id(cls, value: str) -> str:
        import re
        if not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9._-]{0,63}", value):
            raise ValueError("provider_id must be a short stable identifier")
        return value

    @field_validator("model_id")
    @classmethod
    def validate_model_id(cls, value: str) -> str:
        if not value.strip() or any(ord(character) < 32 or ord(character) == 127 for character in value):
            raise ValueError("model_id is invalid")
        return value


class ProviderUpdateRequest(BaseModel):
    """Replace provider settings; preserve the key unless explicitly changed."""

    model_config = ConfigDict(extra="forbid")

    provider_type: Literal["cloud", "local"]
    base_url: str = Field(min_length=1, max_length=2048)
    model_id: str = Field(min_length=1, max_length=256)
    api_key: SecretStr | None = Field(default=None, repr=False)
    clear_api_key: bool = False
    timeout_seconds: float = Field(default=30.0, ge=1.0, le=120.0)
    capabilities: list[Literal["chat"]] = Field(default_factory=lambda: ["chat"], min_length=1, max_length=1)
    enabled: bool = True

    @model_validator(mode="after")
    def validate_key_update(self):
        if self.clear_api_key and self.api_key is not None:
            raise ValueError("api_key and clear_api_key cannot be used together")
        return self

    @field_validator("model_id")
    @classmethod
    def validate_model_id(cls, value: str) -> str:
        if not value.strip() or any(ord(character) < 32 or ord(character) == 127 for character in value):
            raise ValueError("model_id is invalid")
        return value


class ProviderSummary(BaseModel):
    """Safe provider metadata; endpoint URL and credentials are never returned."""

    model_config = ConfigDict(extra="forbid")

    provider_id: str = Field(min_length=1, max_length=64)
    provider_type: Literal["cloud", "local"]
    model_id: str = Field(min_length=1, max_length=256)
    capabilities: list[Literal["chat"]]
    timeout_seconds: float = Field(ge=1.0, le=120.0)
    enabled: bool
    has_api_key: bool


class ProviderListResponse(BaseModel):
    model_config = ConfigDict(extra="forbid")

    providers: list[ProviderSummary] = Field(default_factory=list, max_length=20)


class ProviderDeleteResponse(BaseModel):
    model_config = ConfigDict(extra="forbid")

    deleted: Literal[True] = True
