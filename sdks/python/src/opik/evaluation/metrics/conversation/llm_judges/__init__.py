"""
LLM-judge conversation adapters.

Expose the conversation-focused judges under
`opik.evaluation.metrics.conversation.llm_judges.*`.
"""

from .conversational_coherence.metric import ConversationalCoherenceMetric
from .g_eval_wrappers import (
    ConversationComplianceRiskMetric,
    ConversationDialogueHelpfulnessMetric,
    ConversationPromptUncertaintyMetric,
    ConversationQARelevanceMetric,
    ConversationSummarizationCoherenceMetric,
    ConversationSummarizationConsistencyMetric,
    GEvalConversationMetric,
)
from .session_completeness.metric import SessionCompletenessQuality
from .user_frustration.metric import UserFrustrationMetric

__all__ = [
    "ConversationalCoherenceMetric",
    "ConversationComplianceRiskMetric",
    "ConversationDialogueHelpfulnessMetric",
    "ConversationPromptUncertaintyMetric",
    "ConversationQARelevanceMetric",
    "ConversationSummarizationCoherenceMetric",
    "ConversationSummarizationConsistencyMetric",
    "GEvalConversationMetric",
    "SessionCompletenessQuality",
    "UserFrustrationMetric",
]
