"""
Legacy compatibility shims for conversation metrics.

The conversation metrics package historically hosted the conversation
implementations directly.  The modern layout relocates those modules under
``heuristics.conversation`` and ``llm_judges.conversation`` while the shared
utilities live beside the rest of the metrics.  This module re-exports the new
implementations so older import paths keep working.
"""

from ..conversation_metric_base import ConversationThreadMetric
from ..conversation_turns import build_conversation_turns
from ..conversation_types import Conversation, ConversationDict, ConversationTurn
from ..conversation_helpers import (
    extract_turns_windows_from_conversation,
    get_turns_in_sliding_window,
    merge_turns,
)
from ..heuristics.conversation.degeneration.metric import (
    ConversationDegenerationMetric,
)
from ..heuristics.conversation.knowledge_retention.metric import (
    KnowledgeRetentionMetric,
)
from ..llm_judges.conversation.conversational_coherence.metric import (
    ConversationalCoherenceMetric,
)
from ..llm_judges.conversation.g_eval_wrappers import (
    ConversationComplianceRiskMetric,
    ConversationDialogueHelpfulnessMetric,
    ConversationPromptUncertaintyMetric,
    ConversationQARelevanceMetric,
    ConversationSummarizationCoherenceMetric,
    ConversationSummarizationConsistencyMetric,
    GEvalConversationMetric,
)
from ..llm_judges.conversation.session_completeness.metric import (
    SessionCompletenessQuality,
)
from ..llm_judges.conversation.user_frustration.metric import (
    UserFrustrationMetric,
)

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
