"""Compatibility module for legacy degeneration metric import path."""

from ...heuristics.conversation.degeneration.metric import (
    ConversationDegenerationMetric,
)
from ...heuristics.conversation.degeneration.phrases import DEFAULT_FALLBACK_PHRASES

__all__ = ["ConversationDegenerationMetric", "DEFAULT_FALLBACK_PHRASES"]
