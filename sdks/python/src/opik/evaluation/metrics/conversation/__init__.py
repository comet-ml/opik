"""Compatibility exports for the legacy conversation package."""

from __future__ import annotations

import importlib
from typing import Any

from .conversation_thread_metric import ConversationThreadMetric
from .conversation_turns_factory import build_conversation_turns
from .helpers import (
    extract_turns_windows_from_conversation,
    get_turns_in_sliding_window,
    merge_turns,
)
from .types import Conversation, ConversationDict, ConversationTurn

__all__ = [
    "ConversationThreadMetric",
    "Conversation",
    "ConversationDict",
    "ConversationTurn",
    "build_conversation_turns",
    "extract_turns_windows_from_conversation",
    "get_turns_in_sliding_window",
    "merge_turns",
    "ConversationDegenerationMetric",
    "KnowledgeRetentionMetric",
    "ConversationalCoherenceMetric",
    "SessionCompletenessQuality",
    "UserFrustrationMetric",
    "ConversationComplianceRiskMetric",
    "ConversationDialogueHelpfulnessMetric",
    "ConversationPromptUncertaintyMetric",
    "ConversationQARelevanceMetric",
    "ConversationSummarizationCoherenceMetric",
    "ConversationSummarizationConsistencyMetric",
    "GEvalConversationMetric",
]

_LEGACY_EXPORTS = {
    "ConversationDegenerationMetric": "opik.evaluation.metrics.conversation.degeneration.metric",
    "KnowledgeRetentionMetric": "opik.evaluation.metrics.conversation.knowledge_retention.metric",
    "ConversationalCoherenceMetric": "opik.evaluation.metrics.conversation.conversational_coherence.metric",
    "SessionCompletenessQuality": "opik.evaluation.metrics.conversation.session_completeness.metric",
    "UserFrustrationMetric": "opik.evaluation.metrics.conversation.user_frustration.metric",
    "ConversationComplianceRiskMetric": "opik.evaluation.metrics.conversation.g_eval_wrappers",
    "ConversationDialogueHelpfulnessMetric": "opik.evaluation.metrics.conversation.g_eval_wrappers",
    "ConversationPromptUncertaintyMetric": "opik.evaluation.metrics.conversation.g_eval_wrappers",
    "ConversationQARelevanceMetric": "opik.evaluation.metrics.conversation.g_eval_wrappers",
    "ConversationSummarizationCoherenceMetric": "opik.evaluation.metrics.conversation.g_eval_wrappers",
    "ConversationSummarizationConsistencyMetric": "opik.evaluation.metrics.conversation.g_eval_wrappers",
    "GEvalConversationMetric": "opik.evaluation.metrics.conversation.g_eval_wrappers",
}


def __getattr__(name: str) -> Any:
    if name not in _LEGACY_EXPORTS:
        raise AttributeError(name)

    module = importlib.import_module(_LEGACY_EXPORTS[name])
    value = getattr(module, name)
    globals()[name] = value
    return value
