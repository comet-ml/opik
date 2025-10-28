from __future__ import annotations

from typing import Any, Optional

from ...heuristics import rouge as rouge_metric_module
from ..reference_turn_metric import ConversationReferenceMetric


class RougeConversationMetric(ConversationReferenceMetric):
    """
    Compute conversation-level ROUGE by averaging across turn pairs.

    ``RougeConversationMetric`` compares the configured role between a candidate
    conversation
    and a reference, calling :class:`~opik.evaluation.metrics.heuristics.rouge.ROUGE`
    for the per-turn calculation. The aggregated result includes optional penalties
    when the numbers of evaluated turns differ so you can highlight incomplete
    transcripts.

    Args:
        rouge_metric: Optional ROUGE instance to reuse. When ``None`` a new metric is
            created with ``rouge_kwargs`` forwarded.
        target_role: Conversation role whose turns will be matched. Defaults to
            ``"assistant"``.
        missing_turn_penalty: Penalty applied per unmatched turn. Must be within
            ``[0.0, 1.0]``.
        name: Display name for the metric result. Defaults to
            ``"rouge_conversation_metric"``.
        track: Whether to auto-track results. Defaults to ``True``.
        project_name: Optional project used for tracking. Defaults to ``None``.
        **rouge_kwargs: Extra keyword arguments forwarded to :class:`ROUGE` when
            instantiating a new per-turn metric.

    Example:
        >>> from opik.evaluation.metrics import RougeConversationMetric
        >>> candidate = [
        ...     {"role": "assistant", "content": "Here is your summary."},
        ... ]
        >>> gold = [
        ...     {"role": "assistant", "content": "Here is the requested summary."},
        ... ]
        >>> metric = RougeConversationMetric()
        >>> result = metric.score(
        ...     conversation=candidate,
        ...     reference_conversation=gold,
        ... )
        >>> float(result.value)  # doctest: +SKIP
        0.92
    """

    def __init__(
        self,
        rouge_metric: Optional[rouge_metric_module.ROUGE] = None,
        target_role: str = "assistant",
        missing_turn_penalty: float = 0.0,
        name: str = "rouge_conversation_metric",
        track: bool = True,
        project_name: Optional[str] = None,
        **rouge_kwargs: Any,
    ) -> None:
        turn_metric = rouge_metric or rouge_metric_module.ROUGE(
            track=False, **rouge_kwargs
        )
        super().__init__(
            turn_metric=turn_metric,
            target_role=target_role,
            missing_turn_penalty=missing_turn_penalty,
            name=name,
            track=track,
            project_name=project_name,
        )
