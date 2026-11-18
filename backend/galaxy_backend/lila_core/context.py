"""Bounded assembly of conversation history and user-scoped memory."""

from __future__ import annotations

from pydantic import BaseModel, ConfigDict, Field

from .contracts import CoreTurnRequest
from .memory import (
    ConversationHistoryReader,
    ConversationMessage,
    MemoryReader,
    MemorySnippet,
)


class AssembledContext(BaseModel):
    """Provider-neutral context; memory remains structurally separate from chat."""

    model_config = ConfigDict(extra="forbid", frozen=True)

    conversation_id: str = Field(min_length=1, max_length=128)
    messages: tuple[ConversationMessage, ...]
    relevant_memories: tuple[MemorySnippet, ...] = ()


class ContextAssembler:
    """Build a bounded context without owning persistence or provider behavior."""

    def __init__(
        self,
        history_reader: ConversationHistoryReader,
        memory_reader: MemoryReader,
        *,
        history_limit: int = 20,
        memory_limit: int = 8,
    ) -> None:
        if not 0 <= history_limit <= 100:
            raise ValueError("history_limit must be between 0 and 100")
        if not 0 <= memory_limit <= 20:
            raise ValueError("memory_limit must be between 0 and 20")

        self._history_reader = history_reader
        self._memory_reader = memory_reader
        self._history_limit = history_limit
        self._memory_limit = memory_limit

    async def assemble(
        self,
        request: CoreTurnRequest,
        *,
        principal_id: str,
    ) -> AssembledContext:
        """Load only the authenticated owner's history and relevant memories.

        principal_id must come from the authenticated server dependency, never
        from CoreTurnRequest or another client-controlled field.
        """
        if not isinstance(principal_id, str) or not principal_id.strip():
            raise ValueError("principal_id must be a non-empty authenticated subject")
        if len(principal_id) > 256:
            raise ValueError("principal_id exceeds the supported length")

        history: tuple[ConversationMessage, ...] = ()
        if self._history_limit:
            loaded_history = await self._history_reader.load_recent_messages(
                principal_id=principal_id,
                conversation_id=request.conversation_id,
                limit=self._history_limit,
            )
            validated_history = tuple(
                ConversationMessage.model_validate(message)
                for message in loaded_history
            )
            # Reader contract is chronological. Keep the newest bounded window
            # if an implementation accidentally returns more than requested.
            history = validated_history[-self._history_limit :]

        memories: tuple[MemorySnippet, ...] = ()
        if self._memory_limit:
            loaded_memories = await self._memory_reader.search_relevant_memories(
                principal_id=principal_id,
                query=request.message,
                limit=self._memory_limit,
            )
            validated_memories = tuple(
                MemorySnippet.model_validate(memory)
                for memory in loaded_memories
            )
            memories = validated_memories[: self._memory_limit]

        current_message = ConversationMessage(
            role="user",
            content=request.message,
        )
        return AssembledContext(
            conversation_id=request.conversation_id,
            messages=history + (current_message,),
            relevant_memories=memories,
        )
