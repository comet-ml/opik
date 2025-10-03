from __future__ import annotations

from typing import Any, Optional

from ...heuristics import meteor as meteor_metric_module
from ..reference_turn_metric import ConversationReferenceMetric


class MeteorConversationMetric(ConversationReferenceMetric):
    """
    Aggregate per-turn METEOR scores into a single conversation-level value.

    The metric evaluates corresponding turns between a conversation and its
    reference, delegating the per-turn computation to
    :class:`~opik.evaluation.metrics.heuristics.meteor.METEOR`. Missing turns can be
    penalised to reflect incomplete transcripts while still providing the average
    METEOR score across overlapping turns.

    Args:
        meteor_metric: Optional METEOR instance to reuse. When ``None`` a new metric
            is created and configured via ``meteor_kwargs``.
        target_role: Role whose turns are compared between the candidate and
            reference conversations. Defaults to ``"assistant"``.
        missing_turn_penalty: Deduction applied for each non-overlapping turn count.
            Must be inside ``[0.0, 1.0]``.
        name: Display name for the metric result. Defaults to ``"meteor_c_metric"``.
        track: Whether to track outcomes automatically. Defaults to ``True``.
        project_name: Optional project slug for tracking. Defaults to ``None``.
        **meteor_kwargs: Extra configuration forwarded to :class:`METEOR` when
            instantiating a new per-turn metric.

    Example:
        >>> from opik.evaluation.metrics import MeteorConversationMetric
        >>> convo = [
        ...     {"role": "assistant", "content": "The capital of France is Paris."},
        ... ]
        >>> reference = [
        ...     {"role": "assistant", "content": "Paris is the capital of France."},
        ... ]
        >>> metric = MeteorConversationMetric()
        >>> result = metric.score(conversation=convo, reference_conversation=reference)
        >>> float(result.value)  # doctest: +SKIP
        0.99
    """

    def __init__(
        self,
        meteor_metric: Optional[meteor_metric_module.METEOR] = None,
        target_role: str = "assistant",
        missing_turn_penalty: float = 0.0,
        name: str = "meteor_conversation_metric",
        track: bool = True,
        project_name: Optional[str] = None,
        **meteor_kwargs: Any,
    ) -> None:
        turn_metric = meteor_metric or meteor_metric_module.METEOR(
            track=False, **meteor_kwargs
        )
        super().__init__(
            turn_metric=turn_metric,
            target_role=target_role,
            missing_turn_penalty=missing_turn_penalty,
            name=name,
            track=track,
            project_name=project_name,
        )
