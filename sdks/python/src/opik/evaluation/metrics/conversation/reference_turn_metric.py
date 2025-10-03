from __future__ import annotations

from typing import Optional, Protocol, Sequence, Union

import opik.exceptions as exceptions
from opik.evaluation.metrics import score_result
from opik.evaluation.metrics.conversation import (
    conversation_thread_metric,
    types as conversation_types,
)


class ReferenceTurnMetric(Protocol):
    """Protocol for turn-level metrics that compare output/reference pairs."""

    def score(
        self,
        *,
        output: str,
        reference: str,
        **kwargs: object,
    ) -> Union[score_result.ScoreResult, Sequence[score_result.ScoreResult]]:
        """Score a single candidate/reference pair."""


class ConversationReferenceMetric(
    conversation_thread_metric.ConversationThreadMetric
):
    """
    Base helper that lifts turn-level reference metrics to conversation scope.

    Subclasses supply a turn metric (for example ROUGE, BLEU, METEOR) and the role
    to evaluate. ``ConversationReferenceMetric`` then aligns turns between the
    candidate and reference conversations, averages the per-turn scores, and applies
    an optional penalty when one side has extra turns so gaps are reflected in the
    final score.

    Args:
        turn_metric: The underlying metric used to evaluate individual turn pairs.
        target_role: Conversation role to extract and compare across transcripts.
            Defaults to ``"assistant"``.
        missing_turn_penalty: Amount deducted for each unmatched turn. Must be in
            ``[0.0, 1.0]``.
        name: Display name for the metric. Defaults to
            ``"conversation_reference_metric"``.
        track: Whether the metric should be tracked automatically. Defaults to
            ``True``.
        project_name: Optional Opik project for tracking. Defaults to ``None``.

    Example:
        >>> from opik.evaluation.metrics.conversation.reference_turn_metric import (
        ...     ConversationReferenceMetric,
        ... )
        >>> from opik.evaluation.metrics.heuristics.bleu import SentenceBLEU
        >>> conversation = [
        ...     {"role": "assistant", "content": "Answer"},
        ... ]
        >>> reference = [
        ...     {"role": "assistant", "content": "Reference answer"},
        ... ]
        >>> metric = ConversationReferenceMetric(turn_metric=SentenceBLEU(track=False))
        >>> result = metric.score(conversation, reference)
        >>> float(result.value)  # doctest: +SKIP
        0.74
    """

    def __init__(
        self,
        turn_metric: ReferenceTurnMetric,
        target_role: str = "assistant",
        missing_turn_penalty: float = 0.0,
        name: str = "conversation_reference_metric",
        track: bool = True,
        project_name: Optional[str] = None,
    ) -> None:
        if missing_turn_penalty < 0.0 or missing_turn_penalty > 1.0:
            raise ValueError("missing_turn_penalty must be within [0, 1]")

        super().__init__(name=name, track=track, project_name=project_name)
        self._turn_metric: ReferenceTurnMetric = turn_metric
        self._target_role = target_role
        self._missing_turn_penalty = missing_turn_penalty

    def score(
        self,
        conversation: conversation_types.Conversation,
        reference_conversation: conversation_types.Conversation,
        **kwargs: object,
    ) -> score_result.ScoreResult:
        candidate_turns = self._extract_role_turns(conversation)
        reference_turns = self._extract_role_turns(reference_conversation)

        if not candidate_turns:
            raise exceptions.MetricComputationError(
                "Conversation has no turns for the configured role."
            )
        if not reference_turns:
            raise exceptions.MetricComputationError(
                "Reference conversation has no turns for the configured role."
            )

        overlap = min(len(candidate_turns), len(reference_turns))
        if overlap == 0:
            raise exceptions.MetricComputationError(
                "No overlapping turns between conversation and reference."
            )

        per_turn_scores: list[float] = []
        for idx in range(overlap):
            raw_result = self._turn_metric.score(
                output=candidate_turns[idx], reference=reference_turns[idx]
            )
            result = self._normalize_turn_result(raw_result)
            per_turn_scores.append(result.value)

        average_score = sum(per_turn_scores) / overlap
        missing_turns = abs(len(candidate_turns) - len(reference_turns))
        adjusted_score = max(
            0.0,
            average_score - missing_turns * self._missing_turn_penalty,
        )

        metadata = {
            "per_turn_scores": per_turn_scores,
            "evaluated_turns": overlap,
            "missing_turns": missing_turns,
        }
        return score_result.ScoreResult(
            value=adjusted_score,
            name=self.name,
            reason=(
                f"Average score ({overlap} turns, penalty={self._missing_turn_penalty:.2f}):"
                f" {adjusted_score:.4f}"
            ),
            metadata=metadata,
        )

    def _extract_role_turns(
        self, conversation: conversation_types.Conversation
    ) -> list[str]:
        turns: list[str] = []
        for message in conversation:
            role = message.get("role")
            content = message.get("content")
            if role == self._target_role:
                if content is None or not content.strip():
                    raise exceptions.MetricComputationError(
                        "Encountered empty content for evaluated role."
                    )
                turns.append(content)
        return turns

    def _normalize_turn_result(
        self,
        raw_result: Union[
            score_result.ScoreResult,
            Sequence[score_result.ScoreResult],
        ],
    ) -> score_result.ScoreResult:
        """Ensure the turn metric output is a ScoreResult instance."""

        if isinstance(raw_result, score_result.ScoreResult):
            return raw_result

        if isinstance(raw_result, Sequence):
            if not raw_result:
                raise exceptions.MetricComputationError(
                    "Turn metric returned an empty result sequence."
                )
            first = raw_result[0]
            if isinstance(first, score_result.ScoreResult):
                return first

        raise exceptions.MetricComputationError(
            "Turn metric returned an unexpected result type; expected ScoreResult."
        )
