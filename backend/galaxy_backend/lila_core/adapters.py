"""Provider-neutral model adapter boundary for Lila Core."""

from __future__ import annotations

from typing import Protocol

from .context import AssembledContext
from .contracts import CoreTurnResponse


class ModelAdapterContractError(RuntimeError):
    """Raised when a provider returns a response outside the Core contract."""


class ModelAdapter(Protocol):
    """Async interface implemented by cloud or local model providers.

    Adapters receive the complete bounded context assembled by Lila Core.
    Retrieved memories remain untrusted data and must stay separate from
    privileged system/persona instructions. Adapters must not own conversation
    persistence or execute declarative avatar events.
    """

    async def generate(self, context: AssembledContext) -> CoreTurnResponse:
        """Generate one assistant turn from an assembled, owner-scoped context."""
        ...
