"""Backwards-compatible entry point for conversation type helpers.

Historically ``opik.evaluation.metrics.conversation.types`` exposed the
Conversation/ConversationTurn aliases, but several integrations imported them
via ``opik.evaluation.metrics.conversation_types`` instead.  The module was
removed during the conversation metrics refactor which caused import errors
when the older path was still referenced (e.g. in the Python backend evaluator).
"""

from .conversation import types as _types

# Re-export the public symbols so ``conversation_types.Conversation`` etc.
# continue to function.
Conversation = _types.Conversation
ConversationDict = _types.ConversationDict
ConversationTurn = _types.ConversationTurn

__all__ = ["Conversation", "ConversationDict", "ConversationTurn"]
