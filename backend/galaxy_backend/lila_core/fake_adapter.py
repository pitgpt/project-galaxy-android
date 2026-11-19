"""Deterministic adapter for tests and local development."""

from __future__ import annotations

from .context import AssembledContext
from .contracts import CoreTurnResponse, Emotion


class FakeModelAdapter:
    """A predictable adapter that requires no network or provider credentials."""

    def __init__(
        self,
        response_text: str = "Привет! Я Лила.",
        emotion: Emotion = "neutral",
    ) -> None:
        self._response_text = response_text
        self._emotion = emotion

    async def generate(self, context: AssembledContext) -> CoreTurnResponse:
        return CoreTurnResponse(
            conversation_id=context.conversation_id,
            text=self._response_text,
            emotion=self._emotion,
        )
