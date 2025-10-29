"""Backwards-compatible entry point for conversation type helpers.

Historically ``opik.evaluation.metrics.conversation.types`` exposed the
Conversation/ConversationTurn aliases, but several integrations imported them
via ``opik.evaluation.metrics.conversation_types`` instead.  The module was
removed during the conversation metrics refactor which caused import errors
when the older path was still referenced (e.g. in the Python backend evaluator).
"""

from __future__ import annotations

from typing import Dict, List, Literal, Optional

import pydantic

try:
    from .conversation import types as _types
except ImportError:  # pragma: no cover - legacy fallback
    ConversationDict = Dict[Literal["role", "content"], str]
    Conversation = List[ConversationDict]

    class ConversationTurn(pydantic.BaseModel):
        input: ConversationDict
        output: Optional[ConversationDict]

        def as_list(self) -> List[ConversationDict]:
            if self.output is None:
                return [self.input]
            return [self.input, self.output]
else:
    Conversation = _types.Conversation
    ConversationDict = _types.ConversationDict
    ConversationTurn = _types.ConversationTurn

__all__ = ["Conversation", "ConversationDict", "ConversationTurn"]
