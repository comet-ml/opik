from __future__ import annotations

from typing import Any, Optional

from ...heuristics.bleu import SentenceBLEU
from ..reference_turn_metric import ConversationReferenceMetric


class BleuCMetric(ConversationReferenceMetric):
    """
    Compute conversation-level BLEU by averaging turn-by-turn sentence BLEU scores.

    ``BleuCMetric`` reuses :class:`~opik.evaluation.metrics.heuristics.bleu.SentenceBLEU`
    for the per-turn calculation and then aggregates the results across a specific
    role (assistant by default). If the number of candidate and reference turns
    differs, a configurable penalty is applied for each missing turn.

    Args:
        bleu_metric: Optional pre-configured SentenceBLEU instance. When ``None`` a
            new instance is created using ``bleu_kwargs``.
        target_role: Conversation role whose turns should be evaluated. Defaults to
            ``"assistant"``.
        missing_turn_penalty: Amount deducted when the candidate and reference
            conversations have different turn counts. Must stay in ``[0.0, 1.0]``.
        name: Display name for the metric result. Defaults to ``"bleu_c_metric"``.
        track: Whether to auto-track results in Opik. Defaults to ``True``.
        project_name: Optional tracking project. Defaults to ``None``.
        **bleu_kwargs: Extra configuration forwarded to :class:`SentenceBLEU` when a
            new instance is created.

    Example:
        >>> from opik.evaluation.metrics import BleuCMetric
        >>> candidate = [
        ...     {"role": "assistant", "content": "Sure, here is the summary."},
        ... ]
        >>> reference = [
        ...     {"role": "assistant", "content": "Sure, here is your summary."},
        ... ]
        >>> metric = BleuCMetric()
        >>> result = metric.score(
        ...     conversation=candidate,
        ...     reference_conversation=reference,
        ... )
        >>> float(result.value)  # doctest: +SKIP
        0.86
    """

    def __init__(
        self,
        bleu_metric: Optional[SentenceBLEU] = None,
        target_role: str = "assistant",
        missing_turn_penalty: float = 0.0,
        name: str = "bleu_c_metric",
        track: bool = True,
        project_name: Optional[str] = None,
        **bleu_kwargs: Any,
    ) -> None:
        turn_metric = bleu_metric or SentenceBLEU(track=False, **bleu_kwargs)
        super().__init__(
            turn_metric=turn_metric,
            target_role=target_role,
            missing_turn_penalty=missing_turn_penalty,
            name=name,
            track=track,
            project_name=project_name,
        )
