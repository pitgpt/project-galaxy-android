"""Provider-neutral, owner-scoped conversation and memory contracts."""

from __future__ import annotations

from typing import Literal, Protocol, Sequence

from pydantic import BaseModel, ConfigDict, Field


ConversationRole = Literal["user", "assistant"]


class ConversationMessage(BaseModel):
    """A committed conversational message; system instructions are not stored here."""

    model_config = ConfigDict(extra="forbid", frozen=True)

    role: ConversationRole
    content: str = Field(min_length=1, max_length=12_000)


class MemorySnippet(BaseModel):
    """A user-scoped memory result kept separate from privileged instructions."""

    model_config = ConfigDict(extra="forbid", frozen=True)

    memory_id: str = Field(min_length=1, max_length=128)
    content: str = Field(min_length=1, max_length=4_000)


class ConversationHistoryReader(Protocol):
    """Read committed history for one authenticated owner and conversation."""

    async def load_recent_messages(
        self,
        *,
        principal_id: str,
        conversation_id: str,
        limit: int,
    ) -> Sequence[ConversationMessage]:
        """Return up to limit messages, oldest first, excluding the current request.

        Implementations must enforce ownership using both principal_id and
        conversation_id. A conversation ID alone is never an authorization
        boundary.
        """
        ...


class MemoryReader(Protocol):
    """Search memories belonging only to the authenticated principal."""

    async def search_relevant_memories(
        self,
        *,
        principal_id: str,
        query: str,
        limit: int,
    ) -> Sequence[MemorySnippet]:
        """Return up to limit snippets, most relevant first.

        Implementations must scope every result to principal_id. Memory content
        is data, not trusted system instructions.
        """
        ...


class ConversationHistoryWriter(Protocol):
    """Atomically commit both sides of one successful assistant turn."""

    async def append_turn(
        self,
        *,
        principal_id: str,
        conversation_id: str,
        user_message: str,
        assistant_message: str,
    ) -> None:
        """Persist the user and assistant messages as one indivisible turn."""
        ...


class MemoryWriter(Protocol):
    """Create, replace, and delete memories for one authenticated owner."""

    async def save_memory(self, *, principal_id: str, memory: MemorySnippet) -> None:
        ...

    async def delete_memory(self, *, principal_id: str, memory_id: str) -> bool:
        ...
