"""Provider-neutral model adapter boundary for Lila Core."""

from __future__ import annotations

from typing import Protocol

from .contracts import CoreTurnRequest, CoreTurnResponse


class ModelAdapter(Protocol):
    """Async interface implemented by cloud or local model providers.

    Implementations must not own conversation persistence or avatar execution.
    Those concerns stay in Lila Core services and client adapters respectively.
    """

    async def generate(self, request: CoreTurnRequest) -> CoreTurnResponse:
        """Generate one assistant turn for a normalized request."""
        ...
