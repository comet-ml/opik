from typing import Any

from .aggregated_metric import AggregatedMetric

# Keep the canonical import first for the new layout while still tolerating
# older packaging artefacts (some environments import this module before the
# conversation package is available).  If the eager import fails we fall back
# to the lazy getter below, letting legacy entry-points keep working.
from .conversation.conversation_thread_metric import ConversationThreadMetric

try:
    from .conversation import types as conversation_types
except ImportError:  # pragma: no cover - runtime compatibility
    conversation_types = None  # type: ignore
from .heuristics.conversation.degeneration.metric import ConversationDegenerationMetric
from .heuristics.conversation.knowledge_retention.metric import (
    KnowledgeRetentionMetric,
)
from .llm_judges.conversation.conversational_coherence.metric import (
    ConversationalCoherenceMetric,
)
from .llm_judges.conversation.g_eval_wrappers import (
    GEvalConversationMetric,
    ConversationComplianceRiskMetric,
    ConversationDialogueHelpfulnessMetric,
    ConversationQARelevanceMetric,
    ConversationSummarizationCoherenceMetric,
    ConversationSummarizationConsistencyMetric,
    ConversationPromptUncertaintyMetric,
)
from .llm_judges.conversation.session_completeness.metric import (
    SessionCompletenessQuality,
)
from .llm_judges.conversation.user_frustration.metric import UserFrustrationMetric
from .heuristics.contains import Contains
from .heuristics.equals import Equals
from .heuristics.gleu import GLEU
from .heuristics.chrf import ChrF
from .heuristics.is_json import IsJson
from .heuristics.distribution_metrics import (
    JSDivergence,
    JSDistance,
    KLDivergence,
)
from .heuristics.levenshtein_ratio import LevenshteinRatio
from .heuristics.meteor import METEOR
from .heuristics.bertscore import BERTScore
from .heuristics.spearman import SpearmanRanking
from .heuristics.readability import Readability
from .heuristics.tone import Tone
from .heuristics.prompt_injection import PromptInjection
from .heuristics.language_adherence import LanguageAdherenceMetric
from .heuristics.regex_match import RegexMatch
from .heuristics.bleu import SentenceBLEU, CorpusBLEU
from .heuristics.rouge import ROUGE
from .heuristics.sentiment import Sentiment
from .heuristics.vader_sentiment import VADERSentiment
from .llm_judges.answer_relevance.metric import AnswerRelevance
from .llm_judges.g_eval_presets import (
    AgentTaskCompletionJudge,
    AgentToolCorrectnessJudge,
    ComplianceRiskJudge,
    DemographicBiasJudge,
    DialogueHelpfulnessJudge,
    GenderBiasJudge,
    PoliticalBiasJudge,
    PromptUncertaintyJudge,
    QARelevanceJudge,
    RegionalBiasJudge,
    ReligiousBiasJudge,
    SummarizationCoherenceJudge,
    SummarizationConsistencyJudge,
)
from .llm_judges.context_precision.metric import ContextPrecision
from .llm_judges.context_recall.metric import ContextRecall
from .llm_judges.g_eval.metric import GEval, GEvalPreset
from .llm_judges.hallucination.metric import Hallucination
from .llm_judges.moderation.metric import Moderation
from .llm_judges.llm_juries.metric import LLMJuriesJudge
from .llm_judges.trajectory_accuracy import TrajectoryAccuracy
from .llm_judges.usefulness.metric import Usefulness
from .llm_judges.structure_output_compliance.metric import StructuredOutputCompliance
from .base_metric import BaseMetric
from .ragas_metric import RagasMetricWrapper
from opik.exceptions import MetricComputationError

# from .llm_judges.factuality.metric import Factuality

__all__ = [
    "AggregatedMetric",
    "AnswerRelevance",
    "AgentTaskCompletionJudge",
    "AgentToolCorrectnessJudge",
    "BaseMetric",
    "ConversationDegenerationMetric",
    "KnowledgeRetentionMetric",
    "GEvalConversationMetric",
    "ConversationComplianceRiskMetric",
    "ConversationDialogueHelpfulnessMetric",
    "ConversationQARelevanceMetric",
    "ConversationSummarizationCoherenceMetric",
    "ConversationSummarizationConsistencyMetric",
    "ConversationPromptUncertaintyMetric",
    "conversation_types",
    "ComplianceRiskJudge",
    "Contains",
    "ContextPrecision",
    "ContextRecall",
    "ConversationalCoherenceMetric",
    "CorpusBLEU",
    "DemographicBiasJudge",
    "Equals",
    "GEval",
    "GEvalPreset",
    "GLEU",
    "GenderBiasJudge",
    "Hallucination",
    "IsJson",
    "JSDivergence",
    "JSDistance",
    "KLDivergence",
    "LevenshteinRatio",
    "BERTScore",
    "METEOR",
    "ChrF",
    "Readability",
    "PromptInjection",
    "LanguageAdherenceMetric",
    "PoliticalBiasJudge",
    "PromptUncertaintyJudge",
    "SpearmanRanking",
    "ReligiousBiasJudge",
    "RegionalBiasJudge",
    "VADERSentiment",
    "Tone",
    "StructuredOutputCompliance",
    "MetricComputationError",
    "Moderation",
    "RagasMetricWrapper",
    "RegexMatch",
    "ROUGE",
    "SentenceBLEU",
    "Sentiment",
    "SessionCompletenessQuality",
    "Usefulness",
    "UserFrustrationMetric",
    "TrajectoryAccuracy",
    "DialogueHelpfulnessJudge",
    "QARelevanceJudge",
    "SummarizationCoherenceJudge",
    "SummarizationConsistencyJudge",
    "LLMJuriesJudge",
    "ConversationThreadMetric",
    # "Factuality",
]


def __getattr__(name: str) -> Any:
    # Provide the legacy ``opik.evaluation.metrics.conversation_types`` symbol on
    # demand so environments pinned to the old path keep working after the
    # conversation module refactor.
    if name == "conversation_types":
        from .conversation import types as conv_types

        globals()["conversation_types"] = conv_types
        return conv_types

    raise AttributeError(name)
