from __future__ import annotations

from typing import Optional

import opik.exceptions as exceptions
from opik.evaluation.metrics.base_metric import BaseMetric
from opik.evaluation.metrics.score_result import ScoreResult
from opik.evaluation.metrics.conversation import types as conversation_types


class ConversationReferenceMetric(BaseMetric):
    """Aggregates single-turn reference metrics across a conversation."""

    def __init__(
        self,
        turn_metric: BaseMetric,
        target_role: str = "assistant",
        missing_turn_penalty: float = 0.0,
        name: str = "conversation_reference_metric",
        track: bool = True,
        project_name: Optional[str] = None,
    ) -> None:
        if missing_turn_penalty < 0.0 or missing_turn_penalty > 1.0:
            raise ValueError("missing_turn_penalty must be within [0, 1]")

        super().__init__(name=name, track=track, project_name=project_name)
        self._turn_metric = turn_metric
        self._target_role = target_role
        self._missing_turn_penalty = missing_turn_penalty

    def score(
        self,
        conversation: conversation_types.Conversation,
        reference_conversation: conversation_types.Conversation,
        **kwargs: object,
    ) -> ScoreResult:
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

        per_turn_scores = []
        for idx in range(overlap):
            raw_result = self._turn_metric.score(
                output=candidate_turns[idx], reference=reference_turns[idx]
            )
            if isinstance(raw_result, list):
                if not raw_result:
                    raise exceptions.MetricComputationError(
                        "Turn metric returned an empty result list."
                    )
                result = raw_result[0]
            else:
                result = raw_result

            if not isinstance(result, ScoreResult):
                raise exceptions.MetricComputationError(
                    "Turn metric returned an unexpected result type."
                )
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
        return ScoreResult(
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
