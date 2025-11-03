"""Compatibility module for the conversation degeneration heuristic."""

from ..heuristics.degeneration.metric import ConversationDegenerationMetric
from ..heuristics.degeneration.phrases import DEFAULT_FALLBACK_PHRASES

__all__ = ["ConversationDegenerationMetric", "DEFAULT_FALLBACK_PHRASES"]
