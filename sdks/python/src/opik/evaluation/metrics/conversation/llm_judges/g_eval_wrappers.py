"""Conversation-level adapters for GEval-based judges."""

from __future__ import annotations

from typing import Any, Optional

import opik.exceptions as exceptions

from opik.evaluation.metrics import score_result
from opik.evaluation.metrics.conversation import types as conversation_types
from opik.evaluation.metrics.base_metric import BaseMetric
from opik.evaluation.metrics.conversation.conversation_thread_metric import (
    ConversationThreadMetric,
)
from opik.evaluation.metrics.llm_judges.g_eval_presets import (
    compliance_risk as compliance_presets,
    prompt_uncertainty as prompt_presets,
    qa_suite as qa_presets,
)


class GEvalConversationMetric(ConversationThreadMetric):
    """
    Wrap a GEval-style judge so it can evaluate an entire conversation transcript.

    The wrapper extracts the latest assistant turn from the conversation and sends
    it to the provided judge. Results are normalised into a ``ScoreResult`` so they
    can plug into the wider Opik evaluation pipeline. Any errors raised by the
    underlying judge are captured and reported as a failed score computation.

    The conversation input should match :class:`ConversationThreadMetric`
    semantics—a list of dicts containing ``role`` and ``content`` keys ordered by
    time.

    Args:
        judge: A GEval-compatible metric instance that accepts ``output`` as its
            primary argument.
        name: Optional override for the metric name used in results. When ``None``
            the name is derived from the wrapped judge.

    Returns:
        ScoreResult: Mirrors the wrapped judge's score/value/metadata fields. When
        the judge fails, ``scoring_failed`` is set and ``value`` is ``0.0``.

    Example:
        >>> from opik.evaluation.metrics.conversation.llm_judges.g_eval_wrappers import (
        ...     GEvalConversationMetric,
        ... )
        >>> from opik.evaluation.metrics.llm_judges.g_eval_presets.qa_suite import DialogueHelpfulnessJudge
        >>> conversation = [
        ...     {"role": "user", "content": "Summarise these notes."},
        ...     {"role": "assistant", "content": "Here is a concise summary..."},
        ... ]
        >>> metric = GEvalConversationMetric(judge=DialogueHelpfulnessJudge(model="gpt-4"))
        >>> result = metric.score(conversation)
        >>> result.value  # doctest: +SKIP
        0.83
    """

    def __init__(
        self,
        judge: BaseMetric,
        name: Optional[str] = None,
    ) -> None:
        super().__init__(
            name=name or f"conversation_{judge.name}",
            track=getattr(judge, "track", True),
            project_name=getattr(judge, "project_name", None),
        )
        self._judge = judge

    def _normalize_result(self, raw_result: Any) -> score_result.ScoreResult:
        if isinstance(raw_result, score_result.ScoreResult):
            return raw_result
        if isinstance(raw_result, list):
            if not raw_result:
                raise exceptions.MetricComputationError(
                    "Judge returned an empty list of results."
                )
            first = raw_result[0]
            if isinstance(first, score_result.ScoreResult):
                return first
        raise exceptions.MetricComputationError(
            f"Judge {self._judge.name} returned unsupported result type {type(raw_result)!r}"
        )

    def score(
        self,
        conversation: conversation_types.Conversation,
        **_: Any,
    ) -> score_result.ScoreResult:
        """
        Evaluate the final assistant turn in a conversation.

        Args:
            conversation: Sequence of dict-like turns containing ``role`` and
                ``content`` keys. Only assistant turns with non-empty ``content``
                are considered.

        Returns:
            ScoreResult: Normalised output from the wrapped judge. If no assistant
            message is present, the result is marked as failed with ``value=0.0``.
        """
        last_assistant = next(
            (
                turn.get("content", "")
                for turn in reversed(conversation)
                if turn.get("role") == "assistant"
            ),
            "",
        )
        if not last_assistant.strip():
            return score_result.ScoreResult(
                name=self.name,
                value=0.0,
                reason="Conversation contains no assistant messages to evaluate.",
                scoring_failed=True,
            )

        try:
            raw_result = self._judge.score(output=last_assistant)
        except exceptions.MetricComputationError as error:
            reason = str(error)
        except Exception as error:
            reason = (
                f"Judge {self._judge.name} raised {error.__class__.__name__}: {error}"
            )
        else:
            judge_result = self._normalize_result(raw_result)
            return score_result.ScoreResult(
                name=self.name,
                value=judge_result.value,
                reason=judge_result.reason,
                metadata=judge_result.metadata,
                scoring_failed=judge_result.scoring_failed,
            )

        return score_result.ScoreResult(
            name=self.name,
            value=0.0,
            reason=reason,
            scoring_failed=True,
        )


class ConversationComplianceRiskMetric(GEvalConversationMetric):
    """
    Evaluate the latest assistant response for compliance and risk exposure.

    This metric forwards the final assistant turn to
    :class:`~opik.evaluation.metrics.llm_judges.g_eval_presets.compliance_risk.ComplianceRiskJudge`
    and returns its assessment as a conversation-level ``ScoreResult``.

    Args:
        model: Optional model name or identifier understood by the judge.
        track: Whether to automatically track metric results. Defaults to ``True``.
        project_name: Optional tracking project name. Defaults to ``None``.
        temperature: Sampling temperature supplied to the underlying judge model.

    Returns:
        ScoreResult: Compliance score emitted by the wrapped judge; failed
        evaluations set ``scoring_failed`` and ``value=0.0``.

    Example:
        >>> from opik.evaluation.metrics import ConversationComplianceRiskMetric
        >>> conversation = [
        ...     {"role": "user", "content": "Generate an employment contract."},
        ...     {"role": "assistant", "content": "Here is a standard contract template..."},
        ... ]
        >>> metric = ConversationComplianceRiskMetric(model="gpt-4")
        >>> result = metric.score(conversation)
        >>> result.value  # doctest: +SKIP
        0.12
    """

    def __init__(
        self,
        model: Optional[str] = None,
        track: bool = True,
        project_name: Optional[str] = None,
        temperature: float = 0.0,
    ) -> None:
        super().__init__(
            judge=compliance_presets.ComplianceRiskJudge(
                model=model,
                track=track,
                project_name=project_name,
                temperature=temperature,
            ),
            name="conversation_compliance_risk",
        )


class ConversationDialogueHelpfulnessMetric(GEvalConversationMetric):
    """
    Score how helpful the closing assistant message is within the dialogue.

    The metric expects the same conversation shape as
    :class:`ConversationThreadMetric`. It uses
    :class:`~opik.evaluation.metrics.llm_judges.g_eval_presets.qa_suite.DialogueHelpfulnessJudge`
    to evaluate usefulness and responsiveness of the final assistant turn.

    Args:
        model: Optional model name passed to the judge.
        track: Whether to automatically track results. Defaults to ``True``.
        project_name: Optional tracking project. Defaults to ``None``.
        temperature: Temperature fed into the judge's underlying model.

    Returns:
        ScoreResult: Helpfulness score from the wrapped judge.

    Example:
        >>> from opik.evaluation.metrics import ConversationDialogueHelpfulnessMetric
        >>> conversation = [
        ...     {"role": "user", "content": "How do I reset my password?"},
        ...     {"role": "assistant", "content": "Click the reset link and follow the steps."},
        ... ]
        >>> metric = ConversationDialogueHelpfulnessMetric(model="gpt-4")
        >>> result = metric.score(conversation)
        >>> result.value  # doctest: +SKIP
        0.88
    """

    def __init__(
        self,
        model: Optional[str] = None,
        track: bool = True,
        project_name: Optional[str] = None,
        temperature: float = 0.0,
    ) -> None:
        super().__init__(
            judge=qa_presets.DialogueHelpfulnessJudge(
                model=model,
                track=track,
                project_name=project_name,
                temperature=temperature,
            ),
            name="conversation_dialogue_helpfulness",
        )


class ConversationQARelevanceMetric(GEvalConversationMetric):
    """
    Quantify how relevant the assistant's final answer is to the preceding query.

    This metric expects a conversation sequence compatible with
    :class:`ConversationThreadMetric` and wraps
    :class:`~opik.evaluation.metrics.llm_judges.g_eval_presets.qa_suite.QARelevanceJudge`
    and is useful when the conversation emulates a Q&A exchange.

    Args:
        model: Optional model name used by the judge backend.
        track: Whether to automatically track outcomes. Defaults to ``True``.
        project_name: Optional project for tracked scores. Defaults to ``None``.
        temperature: Judge sampling temperature.

    Returns:
        ScoreResult: Relevance score from the judge.

    Example:
        >>> from opik.evaluation.metrics import ConversationQARelevanceMetric
        >>> conversation = [
        ...     {"role": "user", "content": "Who wrote Dune?"},
        ...     {"role": "assistant", "content": "Frank Herbert wrote Dune."},
        ... ]
        >>> metric = ConversationQARelevanceMetric(model="gpt-4")
        >>> result = metric.score(conversation)
        >>> result.value  # doctest: +SKIP
        1.0
    """

    def __init__(
        self,
        model: Optional[str] = None,
        track: bool = True,
        project_name: Optional[str] = None,
        temperature: float = 0.0,
    ) -> None:
        super().__init__(
            judge=qa_presets.QARelevanceJudge(
                model=model,
                track=track,
                project_name=project_name,
                temperature=temperature,
            ),
            name="conversation_qa_relevance",
        )


class ConversationSummarizationCoherenceMetric(GEvalConversationMetric):
    """
    Assess the coherence of a summary-style assistant response.

    The metric expects the conversation schema defined by
    :class:`ConversationThreadMetric` and invokes
    :class:`~opik.evaluation.metrics.llm_judges.g_eval_presets.qa_suite.SummarizationCoherenceJudge`
    to rate whether the summary flows naturally and captures the conversation
    structure.

    Args:
        model: Optional model name or identifier for the judge.
        track: Whether to track metric results automatically. Defaults to ``True``.
        project_name: Optional project name for tracked scores. Defaults to ``None``.
        temperature: Sampling temperature passed to the judge model.

    Returns:
        ScoreResult: Coherence score from the judge.

    Example:
        >>> from opik.evaluation.metrics import ConversationSummarizationCoherenceMetric
        >>> conversation = [
        ...     {"role": "user", "content": "Summarise this chat."},
        ...     {"role": "assistant", "content": "Summary: we discussed timelines and budgets."},
        ... ]
        >>> metric = ConversationSummarizationCoherenceMetric(model="gpt-4")
        >>> result = metric.score(conversation)
        >>> result.value  # doctest: +SKIP
        0.91
    """

    def __init__(
        self,
        model: Optional[str] = None,
        track: bool = True,
        project_name: Optional[str] = None,
        temperature: float = 0.0,
    ) -> None:
        super().__init__(
            judge=qa_presets.SummarizationCoherenceJudge(
                model=model,
                track=track,
                project_name=project_name,
                temperature=temperature,
            ),
            name="conversation_summarization_coherence",
        )


class ConversationSummarizationConsistencyMetric(GEvalConversationMetric):
    """
    Check whether a dialogue summary stays faithful to the source turns.

    The metric assumes the standard conversation schema and delegates scoring to
    :class:`~opik.evaluation.metrics.llm_judges.g_eval_presets.qa_suite.SummarizationConsistencyJudge`
    and reports the result at the conversation level.

    Args:
        model: Optional model name passed through to the judge.
        track: Whether to automatically track results. Defaults to ``True``.
        project_name: Optional tracking project. Defaults to ``None``.
        temperature: Temperature parameter supplied to the judge model.

    Returns:
        ScoreResult: Consistency score from the judge.

    Example:
        >>> from opik.evaluation.metrics import ConversationSummarizationConsistencyMetric
        >>> conversation = [
        ...     {"role": "user", "content": "Give me a summary."},
        ...     {"role": "assistant", "content": "Summary: project ships next week."},
        ... ]
        >>> metric = ConversationSummarizationConsistencyMetric(model="gpt-4")
        >>> result = metric.score(conversation)
        >>> result.value  # doctest: +SKIP
        0.95
    """

    def __init__(
        self,
        model: Optional[str] = None,
        track: bool = True,
        project_name: Optional[str] = None,
        temperature: float = 0.0,
    ) -> None:
        super().__init__(
            judge=qa_presets.SummarizationConsistencyJudge(
                model=model,
                track=track,
                project_name=project_name,
                temperature=temperature,
            ),
            name="conversation_summarization_consistency",
        )


class ConversationPromptUncertaintyMetric(GEvalConversationMetric):
    """
    Measure how uncertain the assistant appears about executing the prompt.

    The metric expects the standard conversation schema and pipes the latest
    assistant reply into
    :class:`~opik.evaluation.metrics.llm_judges.g_eval_presets.prompt_diagnostics.PromptUncertaintyJudge`
    and returns the judge's score in a conversation-friendly format.

    Args:
        model: Optional model name for the judge to use.
        track: Whether to automatically track the metric. Defaults to ``True``.
        project_name: Optional tracking project. Defaults to ``None``.
        temperature: Sampling temperature for the judge model.

    Returns:
        ScoreResult: Uncertainty score from the judge.

    Example:
        >>> from opik.evaluation.metrics import ConversationPromptUncertaintyMetric
        >>> conversation = [
        ...     {"role": "user", "content": "Follow the brief precisely."},
        ...     {"role": "assistant", "content": "I'm not fully certain which part to prioritise."},
        ... ]
        >>> metric = ConversationPromptUncertaintyMetric(model="gpt-4")
        >>> result = metric.score(conversation)
        >>> result.value  # doctest: +SKIP
        0.42
    """

    def __init__(
        self,
        model: Optional[str] = None,
        track: bool = True,
        project_name: Optional[str] = None,
        temperature: float = 0.0,
    ) -> None:
        super().__init__(
            judge=prompt_presets.PromptUncertaintyJudge(
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
    "ConversationPromptUncertaintyMetric",
]
