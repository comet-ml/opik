# Lazy-loaded metric classes (PEP 562).
# All imports are deferred until first access via __getattr__.
from .base_metric import BaseMetric
from . import score_result as score_result  # noqa: F401 — re-exported for public use

_LAZY_IMPORTS: dict = {
    # aggregated
    "AggregatedMetric": (".aggregated_metric", "AggregatedMetric"),
    # conversation
    "ConversationThreadMetric": (".conversation.conversation_thread_metric", "ConversationThreadMetric"),
    "conversation_types": (".conversation", "types"),
    "ConversationDegenerationMetric": (".conversation.heuristics.degeneration.metric", "ConversationDegenerationMetric"),
    "KnowledgeRetentionMetric": (".conversation.heuristics.knowledge_retention.metric", "KnowledgeRetentionMetric"),
    "ConversationalCoherenceMetric": (".conversation.llm_judges.conversational_coherence.metric", "ConversationalCoherenceMetric"),
    "GEvalConversationMetric": (".conversation.llm_judges.g_eval_wrappers", "GEvalConversationMetric"),
    "ConversationComplianceRiskMetric": (".conversation.llm_judges.g_eval_wrappers", "ConversationComplianceRiskMetric"),
    "ConversationDialogueHelpfulnessMetric": (".conversation.llm_judges.g_eval_wrappers", "ConversationDialogueHelpfulnessMetric"),
    "ConversationQARelevanceMetric": (".conversation.llm_judges.g_eval_wrappers", "ConversationQARelevanceMetric"),
    "ConversationSummarizationCoherenceMetric": (".conversation.llm_judges.g_eval_wrappers", "ConversationSummarizationCoherenceMetric"),
    "ConversationSummarizationConsistencyMetric": (".conversation.llm_judges.g_eval_wrappers", "ConversationSummarizationConsistencyMetric"),
    "ConversationPromptUncertaintyMetric": (".conversation.llm_judges.g_eval_wrappers", "ConversationPromptUncertaintyMetric"),
    "SessionCompletenessQuality": (".conversation.llm_judges.session_completeness.metric", "SessionCompletenessQuality"),
    "UserFrustrationMetric": (".conversation.llm_judges.user_frustration.metric", "UserFrustrationMetric"),
    # heuristics
    "Contains": (".heuristics.contains", "Contains"),
    "Equals": (".heuristics.equals", "Equals"),
    "GLEU": (".heuristics.gleu", "GLEU"),
    "ChrF": (".heuristics.chrf", "ChrF"),
    "IsJson": (".heuristics.is_json", "IsJson"),
    "JSDivergence": (".heuristics.distribution_metrics", "JSDivergence"),
    "JSDistance": (".heuristics.distribution_metrics", "JSDistance"),
    "KLDivergence": (".heuristics.distribution_metrics", "KLDivergence"),
    "LevenshteinRatio": (".heuristics.levenshtein_ratio", "LevenshteinRatio"),
    "METEOR": (".heuristics.meteor", "METEOR"),
    "BERTScore": (".heuristics.bertscore", "BERTScore"),
    "SpearmanRanking": (".heuristics.spearman", "SpearmanRanking"),
    "Readability": (".heuristics.readability", "Readability"),
    "Tone": (".heuristics.tone", "Tone"),
    "PromptInjection": (".heuristics.prompt_injection", "PromptInjection"),
    "LanguageAdherenceMetric": (".heuristics.language_adherence", "LanguageAdherenceMetric"),
    "RegexMatch": (".heuristics.regex_match", "RegexMatch"),
    "SentenceBLEU": (".heuristics.bleu", "SentenceBLEU"),
    "CorpusBLEU": (".heuristics.bleu", "CorpusBLEU"),
    "ROUGE": (".heuristics.rouge", "ROUGE"),
    "Sentiment": (".heuristics.sentiment", "Sentiment"),
    "VADERSentiment": (".heuristics.vader_sentiment", "VADERSentiment"),
    # llm judges
    "AnswerRelevance": (".llm_judges.answer_relevance.metric", "AnswerRelevance"),
    "AgentTaskCompletionJudge": (".llm_judges.g_eval_presets", "AgentTaskCompletionJudge"),
    "AgentToolCorrectnessJudge": (".llm_judges.g_eval_presets", "AgentToolCorrectnessJudge"),
    "ComplianceRiskJudge": (".llm_judges.g_eval_presets", "ComplianceRiskJudge"),
    "DemographicBiasJudge": (".llm_judges.g_eval_presets", "DemographicBiasJudge"),
    "DialogueHelpfulnessJudge": (".llm_judges.g_eval_presets", "DialogueHelpfulnessJudge"),
    "GenderBiasJudge": (".llm_judges.g_eval_presets", "GenderBiasJudge"),
    "PoliticalBiasJudge": (".llm_judges.g_eval_presets", "PoliticalBiasJudge"),
    "PromptUncertaintyJudge": (".llm_judges.g_eval_presets", "PromptUncertaintyJudge"),
    "QARelevanceJudge": (".llm_judges.g_eval_presets", "QARelevanceJudge"),
    "RegionalBiasJudge": (".llm_judges.g_eval_presets", "RegionalBiasJudge"),
    "ReligiousBiasJudge": (".llm_judges.g_eval_presets", "ReligiousBiasJudge"),
    "SummarizationCoherenceJudge": (".llm_judges.g_eval_presets", "SummarizationCoherenceJudge"),
    "SummarizationConsistencyJudge": (".llm_judges.g_eval_presets", "SummarizationConsistencyJudge"),
    "ContextPrecision": (".llm_judges.context_precision.metric", "ContextPrecision"),
    "ContextRecall": (".llm_judges.context_recall.metric", "ContextRecall"),
    "GEval": (".llm_judges.g_eval.metric", "GEval"),
    "GEvalPreset": (".llm_judges.g_eval.metric", "GEvalPreset"),
    "Hallucination": (".llm_judges.hallucination.metric", "Hallucination"),
    "Moderation": (".llm_judges.moderation.metric", "Moderation"),
    "LLMJuriesJudge": (".llm_judges.llm_juries.metric", "LLMJuriesJudge"),
    "TrajectoryAccuracy": (".llm_judges.trajectory_accuracy", "TrajectoryAccuracy"),
    "SycEval": (".llm_judges.syc_eval.metric", "SycEval"),
    "Usefulness": (".llm_judges.usefulness.metric", "Usefulness"),
    "StructuredOutputCompliance": (".llm_judges.structure_output_compliance.metric", "StructuredOutputCompliance"),
    # wrappers
    "RagasMetricWrapper": (".ragas_metric", "RagasMetricWrapper"),
    # exceptions
    "MetricComputationError": ("opik.exceptions", "MetricComputationError"),
}

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
    "SycEval",
    "Usefulness",
    "UserFrustrationMetric",
    "TrajectoryAccuracy",
    "DialogueHelpfulnessJudge",
    "QARelevanceJudge",
    "SummarizationCoherenceJudge",
    "SummarizationConsistencyJudge",
    "LLMJuriesJudge",
    "ConversationThreadMetric",
]


def __getattr__(name: str):
    if name in _LAZY_IMPORTS:
        module_path, attr_name = _LAZY_IMPORTS[name]

        import importlib

        module = importlib.import_module(module_path, __package__)
        value = getattr(module, attr_name)

        # Cache in module globals so __getattr__ is not called again
        globals()[name] = value
        return value

    raise AttributeError(f"module 'opik.evaluation.metrics' has no attribute {name!r}")


def __dir__():
    return list(__all__) + ["score_result"]
