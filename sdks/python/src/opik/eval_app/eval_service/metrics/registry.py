"""Registry for available metrics."""

import logging
from typing import Any, Dict, List, Optional, Type

from opik.evaluation import metrics
from opik.evaluation.metrics import base_metric

from .descriptor import MetricDescriptor, MetricInfo

LOGGER = logging.getLogger(__name__)

_default_registry: Optional["MetricsRegistry"] = None


class MetricsRegistry:
    """Registry of available metrics."""

    def __init__(self) -> None:
        self._metrics: Dict[str, MetricDescriptor] = {}

    def register(
        self,
        metric_class: Type[base_metric.BaseMetric],
        init_defaults: Optional[Dict[str, Any]] = None,
    ) -> None:
        """Register a metric class.

        Args:
            metric_class: The metric class to register.
            init_defaults: Optional custom default values for __init__ parameters.
        """
        descriptor = MetricDescriptor(
            metric_class=metric_class,
            init_defaults=init_defaults,
        )
        self._metrics[metric_class.__name__] = descriptor
        LOGGER.debug("Registered metric: %s", metric_class.__name__)

    def get(self, name: str) -> Optional[MetricDescriptor]:
        """Get a metric descriptor by name."""
        return self._metrics.get(name)

    def list_all(self) -> List[MetricInfo]:
        """List all registered metrics."""
        return [descriptor.to_metric_info() for descriptor in self._metrics.values()]

    def get_metric_class(self, name: str) -> Optional[Type[base_metric.BaseMetric]]:
        """Get a metric class by name."""
        descriptor = self._metrics.get(name)
        return descriptor.metric_class if descriptor else None


def create_default_registry() -> MetricsRegistry:
    """Create a registry with default metrics.

    Note: The following metrics are NOT included because they require complex
    input types that cannot be provided via simple field mapping:

    - AggregatedMetric: Wrapper class, not a standalone metric
    - RagasMetricWrapper: Wrapper class for Ragas metrics
    - LLMJuriesJudge: Uses *args/**kwargs, needs special handling

    Conversation metrics (require conversation_types.Conversation or List of turns):
    - ConversationComplianceRiskMetric
    - ConversationDegenerationMetric
    - ConversationDialogueHelpfulnessMetric
    - ConversationPromptUncertaintyMetric
    - ConversationQARelevanceMetric
    - ConversationSummarizationCoherenceMetric
    - ConversationSummarizationConsistencyMetric
    - ConversationThreadMetric
    - ConversationalCoherenceMetric
    - GEvalConversationMetric
    - KnowledgeRetentionMetric
    - UserFrustrationMetric
    - SessionCompletenessQuality

    Metrics requiring special data structures:
    - CorpusBLEU: Requires List of outputs and references (corpus-level)
    - SpearmanRanking: Requires Sequence of rankings
    - TrajectoryAccuracy: Requires List of agent trajectory steps
    """
    registry = MetricsRegistry()

    # Heuristic metrics (no LLM required)
    registry.register(metrics.Equals)
    registry.register(metrics.Contains)
    registry.register(metrics.RegexMatch)
    registry.register(metrics.IsJson)
    registry.register(metrics.LevenshteinRatio)

    # Text similarity metrics
    registry.register(metrics.ROUGE)
    registry.register(metrics.METEOR)
    registry.register(metrics.SentenceBLEU)
    registry.register(metrics.ChrF)
    registry.register(metrics.GLEU)
    registry.register(metrics.BERTScore)

    # Statistical distance metrics (compare probability distributions as strings)
    registry.register(metrics.JSDistance)
    registry.register(metrics.JSDivergence)
    registry.register(metrics.KLDivergence)

    # Sentiment and readability
    registry.register(metrics.Sentiment)
    registry.register(metrics.VADERSentiment)
    registry.register(metrics.Readability)
    registry.register(metrics.Tone)
    registry.register(metrics.LanguageAdherenceMetric)

    # LLM Judge metrics (default to gpt-4o-mini)
    llm_defaults = {"model": "gpt-4o-mini"}
    registry.register(metrics.AnswerRelevance, init_defaults=llm_defaults)
    registry.register(metrics.ContextPrecision, init_defaults=llm_defaults)
    registry.register(metrics.ContextRecall, init_defaults=llm_defaults)
    registry.register(metrics.Hallucination, init_defaults=llm_defaults)
    registry.register(metrics.Moderation, init_defaults=llm_defaults)
    registry.register(metrics.GEval, init_defaults=llm_defaults)
    registry.register(metrics.GEvalPreset, init_defaults=llm_defaults)
    registry.register(metrics.Usefulness, init_defaults=llm_defaults)
    registry.register(metrics.StructuredOutputCompliance, init_defaults=llm_defaults)
    registry.register(metrics.SycEval, init_defaults=llm_defaults)

    # Safety and compliance metrics
    registry.register(metrics.PromptInjection, init_defaults=llm_defaults)
    registry.register(metrics.ComplianceRiskJudge, init_defaults=llm_defaults)

    # QA and dialogue metrics
    registry.register(metrics.QARelevanceJudge, init_defaults=llm_defaults)
    registry.register(metrics.DialogueHelpfulnessJudge, init_defaults=llm_defaults)
    registry.register(metrics.PromptUncertaintyJudge, init_defaults=llm_defaults)

    # Bias detection metrics
    registry.register(metrics.GenderBiasJudge, init_defaults=llm_defaults)
    registry.register(metrics.PoliticalBiasJudge, init_defaults=llm_defaults)
    registry.register(metrics.DemographicBiasJudge, init_defaults=llm_defaults)
    registry.register(metrics.RegionalBiasJudge, init_defaults=llm_defaults)
    registry.register(metrics.ReligiousBiasJudge, init_defaults=llm_defaults)

    # Summarization metrics
    registry.register(metrics.SummarizationCoherenceJudge, init_defaults=llm_defaults)
    registry.register(metrics.SummarizationConsistencyJudge, init_defaults=llm_defaults)

    # Agent metrics
    registry.register(metrics.AgentTaskCompletionJudge, init_defaults=llm_defaults)
    registry.register(metrics.AgentToolCorrectnessJudge, init_defaults=llm_defaults)

    return registry


def get_default_registry() -> MetricsRegistry:
    """Get the default metrics registry singleton."""
    global _default_registry
    if _default_registry is None:
        _default_registry = create_default_registry()
    return _default_registry
