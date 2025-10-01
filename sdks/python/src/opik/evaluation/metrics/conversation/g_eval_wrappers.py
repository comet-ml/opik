"""Conversation-level adapters for GEval-based judges."""

from __future__ import annotations

from typing import Any, Optional

import opik.exceptions as exceptions

from ..base_metric import BaseMetric
from ..score_result import ScoreResult
from ..llm_judges.qa_suite import (
    DialogueHelpfulnessJudge,
    QARelevanceJudge,
    SummarizationCoherenceJudge,
    SummarizationConsistencyJudge,
)
from ..llm_judges.compliance_risk.metric import ComplianceRiskJudge
from ..llm_judges.prompt_diagnostics.metric import (
    PromptPerplexityJudge,
    PromptUncertaintyJudge,
)
from . import conversation_thread_metric, types as conversation_types


class GEvalConversationMetric(conversation_thread_metric.ConversationThreadMetric):
    """Wrap a GEval-based judge so it can score whole conversations."""

    def __init__(
        self,
        judge: BaseMetric,
        name: Optional[str] = None,
    ) -> None:
        super().__init__(
            name=name or f"conversation_{judge.name}",
            track=judge.track,
            project_name=judge.project_name,
        )
        self._judge = judge

    def score(
        self,
        conversation: conversation_types.Conversation,
        **_: Any,
    ) -> ScoreResult:
        last_assistant = next(
            (
                turn.get("content", "")
                for turn in reversed(conversation)
                if turn.get("role") == "assistant"
            ),
            "",
        )
        if not last_assistant.strip():
            return ScoreResult(
                name=self.name,
                value=0.0,
                reason="Conversation contains no assistant messages to evaluate.",
                scoring_failed=True,
            )

        try:
            judge_result = self._judge.score(output=last_assistant)
        except exceptions.MetricComputationError as error:
            return ScoreResult(
                name=self.name,
                value=0.0,
                reason=str(error),
                scoring_failed=True,
            )

        return ScoreResult(
            name=self.name,
            value=judge_result.value,
            reason=judge_result.reason,
            metadata=judge_result.metadata,
            scoring_failed=judge_result.scoring_failed,
        )


class ConversationComplianceRiskMetric(GEvalConversationMetric):
    def __init__(
        self,
        model: Optional[str] = None,
        track: bool = True,
        project_name: Optional[str] = None,
        temperature: float = 0.0,
    ) -> None:
        super().__init__(
            judge=ComplianceRiskJudge(
                model=model,
                track=track,
                project_name=project_name,
                temperature=temperature,
            ),
            name="conversation_compliance_risk",
        )


class ConversationDialogueHelpfulnessMetric(GEvalConversationMetric):
    def __init__(
        self,
        model: Optional[str] = None,
        track: bool = True,
        project_name: Optional[str] = None,
        temperature: float = 0.0,
    ) -> None:
        super().__init__(
            judge=DialogueHelpfulnessJudge(
                model=model,
                track=track,
                project_name=project_name,
                temperature=temperature,
            ),
            name="conversation_dialogue_helpfulness",
        )


class ConversationQARelevanceMetric(GEvalConversationMetric):
    def __init__(
        self,
        model: Optional[str] = None,
        track: bool = True,
        project_name: Optional[str] = None,
        temperature: float = 0.0,
    ) -> None:
        super().__init__(
            judge=QARelevanceJudge(
                model=model,
                track=track,
                project_name=project_name,
                temperature=temperature,
            ),
            name="conversation_qa_relevance",
        )


class ConversationSummarizationCoherenceMetric(GEvalConversationMetric):
    def __init__(
        self,
        model: Optional[str] = None,
        track: bool = True,
        project_name: Optional[str] = None,
        temperature: float = 0.0,
    ) -> None:
        super().__init__(
            judge=SummarizationCoherenceJudge(
                model=model,
                track=track,
                project_name=project_name,
                temperature=temperature,
            ),
            name="conversation_summarization_coherence",
        )


class ConversationSummarizationConsistencyMetric(GEvalConversationMetric):
    def __init__(
        self,
        model: Optional[str] = None,
        track: bool = True,
        project_name: Optional[str] = None,
        temperature: float = 0.0,
    ) -> None:
        super().__init__(
            judge=SummarizationConsistencyJudge(
                model=model,
                track=track,
                project_name=project_name,
                temperature=temperature,
            ),
            name="conversation_summarization_consistency",
        )


class ConversationPromptPerplexityMetric(GEvalConversationMetric):
    def __init__(
        self,
        model: Optional[str] = None,
        track: bool = True,
        project_name: Optional[str] = None,
        temperature: float = 0.0,
    ) -> None:
        super().__init__(
            judge=PromptPerplexityJudge(
                model=model,
                track=track,
                project_name=project_name,
                temperature=temperature,
            ),
            name="conversation_prompt_perplexity",
        )


class ConversationPromptUncertaintyMetric(GEvalConversationMetric):
    def __init__(
        self,
        model: Optional[str] = None,
        track: bool = True,
        project_name: Optional[str] = None,
        temperature: float = 0.0,
    ) -> None:
        super().__init__(
            judge=PromptUncertaintyJudge(
                model=model,
                track=track,
                project_name=project_name,
                temperature=temperature,
            ),
            name="conversation_prompt_uncertainty",
        )


__all__ = [
    "GEvalConversationMetric",
    "ConversationComplianceRiskMetric",
    "ConversationDialogueHelpfulnessMetric",
    "ConversationQARelevanceMetric",
    "ConversationSummarizationCoherenceMetric",
    "ConversationSummarizationConsistencyMetric",
    "ConversationPromptPerplexityMetric",
    "ConversationPromptUncertaintyMetric",
]
