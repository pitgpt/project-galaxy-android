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
    """Assistant output plus actual provider identity and explicit fallback disclosure."""

    model_config = ConfigDict(extra="forbid")

    conversation_id: str = Field(min_length=1, max_length=128)
    text: str = Field(min_length=1, max_length=12_000)
    emotion: Emotion = "neutral"
    avatar_events: list[AvatarEvent] = Field(default_factory=list)
    provider_id: str | None = Field(default=None, min_length=1, max_length=64)
    model_id: str | None = Field(default=None, min_length=1, max_length=256)
    fallback_used: bool = False
    fallback_from_provider_id: str | None = Field(default=None, min_length=1, max_length=64)
    fallback_from_model_id: str | None = Field(default=None, min_length=1, max_length=256)
    fallback_reason: Literal[
        "primary_connection_unavailable",
        "primary_provider_unavailable",
    ] | None = None

    @model_validator(mode="after")
    def validate_fallback_disclosure(self):
        metadata = (
            self.fallback_from_provider_id,
            self.fallback_from_model_id,
            self.fallback_reason,
        )
        if self.fallback_used:
            if (
                self.provider_id is None
                or self.model_id is None
                or any(value is None for value in metadata)
                or self.provider_id == self.fallback_from_provider_id
            ):
                raise ValueError("fallback response must identify both actual and primary models")
        elif any(value is not None for value in metadata):
            raise ValueError("fallback metadata is only valid when fallback_used is true")
        return self



class IdempotentTurnStatusResponse(BaseModel):
    """Owner-scoped status and optional replay of one committed idempotent turn."""

    model_config = ConfigDict(extra="forbid")

    conversation_id: str = Field(min_length=1, max_length=128)
    status: Literal["pending", "outcome_unknown", "completed"]
    response: CoreTurnResponse | None = None

    @model_validator(mode="after")
    def validate_response_matches_status(self):
        if (self.status == "completed") != (self.response is not None):
            raise ValueError("completed turn status must include exactly one response")
        if self.response is not None and self.response.conversation_id != self.conversation_id:
            raise ValueError("turn response belongs to another conversation")
        return self


class ConversationHistoryItem(BaseModel):
    """One decrypted message plus the actual model/fallback identity used for it."""

    model_config = ConfigDict(extra="forbid")

    sequence: int = Field(ge=1)
    role: Literal["user", "assistant"]
    content: str = Field(min_length=1, max_length=12_000)
    created_at_epoch_millis: int = Field(ge=0)
    provider_id: str | None = Field(default=None, min_length=1, max_length=64)
    model_id: str | None = Field(default=None, min_length=1, max_length=256)
    fallback_used: bool = False
    fallback_from_provider_id: str | None = Field(default=None, min_length=1, max_length=64)
    fallback_from_model_id: str | None = Field(default=None, min_length=1, max_length=256)
    fallback_reason: Literal[
        "primary_connection_unavailable",
        "primary_provider_unavailable",
    ] | None = None

    @model_validator(mode="after")
    def validate_fallback_disclosure(self):
        metadata = (
            self.fallback_from_provider_id,
            self.fallback_from_model_id,
            self.fallback_reason,
        )
        if self.fallback_used:
            if (
                self.role != "assistant"
                or self.provider_id is None
                or self.model_id is None
                or any(value is None for value in metadata)
                or self.provider_id == self.fallback_from_provider_id
            ):
                raise ValueError("fallback history must identify actual and primary models")
        elif any(value is not None for value in metadata):
            raise ValueError("fallback metadata is only valid when fallback_used is true")
        return self


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



class ProviderConnectionTestResponse(BaseModel):
    """Safe result of a non-generative owner-managed provider connection test."""

    model_config = ConfigDict(extra="forbid")

    provider_id: str = Field(min_length=1, max_length=64)
    configured_model_id: str = Field(min_length=1, max_length=256)
    connection_reachable: bool
    model_discovery_supported: bool
    configured_model_available: bool | None = None
    model_ids: list[str] = Field(default_factory=list, max_length=100)
    chat_completion_verified: Literal[False] = False


class ProviderChatTestResponse(BaseModel):
    """Result of an explicit, billable chat-completion smoke test."""

    model_config = ConfigDict(extra="forbid")

    provider_id: str = Field(min_length=1, max_length=64)
    model_id: str = Field(min_length=1, max_length=256)
    chat_completion_verified: Literal[True] = True
    response_characters: int = Field(ge=1, le=12_000)


class ProviderCreateRequest(BaseModel):
    """Create an owner-scoped provider without returning its endpoint or secret."""

    model_config = ConfigDict(extra="forbid")

    provider_id: str = Field(min_length=1, max_length=64)
    provider_type: Literal["cloud", "local"]
    base_url: str = Field(min_length=1, max_length=2048)
    model_id: str = Field(min_length=1, max_length=256)
    api_key: SecretStr | None = Field(default=None)
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

    @field_validator("api_key")
    @classmethod
    def validate_api_key(cls, value: SecretStr | None) -> SecretStr | None:
        if value is not None and not value.get_secret_value().strip():
            raise ValueError("api_key must not be empty")
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
    api_key: SecretStr | None = Field(default=None)
    clear_api_key: bool = False
    timeout_seconds: float = Field(default=30.0, ge=1.0, le=120.0)
    capabilities: list[Literal["chat"]] = Field(default_factory=lambda: ["chat"], min_length=1, max_length=1)
    enabled: bool = True

    @model_validator(mode="after")
    def validate_key_update(self):
        if self.clear_api_key and self.api_key is not None:
            raise ValueError("api_key and clear_api_key cannot be used together")
        return self

    @field_validator("api_key")
    @classmethod
    def validate_api_key(cls, value: SecretStr | None) -> SecretStr | None:
        if value is not None and not value.get_secret_value().strip():
            raise ValueError("api_key must not be empty")
        return value

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


class ConversationModelSelectionRequest(BaseModel):
    """Select a primary provider and an optional, explicitly disclosed fallback."""

    model_config = ConfigDict(extra="forbid")

    provider_id: str = Field(min_length=1, max_length=64)
    model_id: str = Field(min_length=1, max_length=256)
    fallback_provider_id: str | None = Field(default=None, min_length=1, max_length=64)
    fallback_model_id: str | None = Field(default=None, min_length=1, max_length=256)

    @field_validator("provider_id", "fallback_provider_id")
    @classmethod
    def validate_provider_id(cls, value: str | None) -> str | None:
        if value is None:
            return None
        import re
        if not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9._-]{0,63}", value):
            raise ValueError("provider_id must be a short stable identifier")
        return value

    @field_validator("model_id", "fallback_model_id")
    @classmethod
    def validate_model_id(cls, value: str | None) -> str | None:
        if value is None:
            return None
        if not value.strip() or any(ord(character) < 32 or ord(character) == 127 for character in value):
            raise ValueError("model_id is invalid")
        return value

    @model_validator(mode="after")
    def validate_fallback_pair(self):
        if (self.fallback_provider_id is None) != (self.fallback_model_id is None):
            raise ValueError("fallback provider and model must be configured together")
        if self.fallback_provider_id == self.provider_id and self.fallback_provider_id is not None:
            raise ValueError("fallback must use a different provider")
        return self


class ConversationModelSelectionResponse(BaseModel):
    """Non-secret owner-scoped primary and optional fallback selection."""

    model_config = ConfigDict(extra="forbid")

    conversation_id: str = Field(min_length=1, max_length=128)
    selected: bool
    provider_id: str | None = Field(default=None, min_length=1, max_length=64)
    model_id: str | None = Field(default=None, min_length=1, max_length=256)
    fallback_provider_id: str | None = Field(default=None, min_length=1, max_length=64)
    fallback_model_id: str | None = Field(default=None, min_length=1, max_length=256)
