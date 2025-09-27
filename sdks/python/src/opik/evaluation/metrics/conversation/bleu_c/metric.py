from __future__ import annotations

from typing import Any, Optional

from ...heuristics.bleu import SentenceBLEU
from ..reference_turn_metric import ConversationReferenceMetric


class BleuCMetric(ConversationReferenceMetric):
    """Aggregates sentence-level BLEU over assistant turns."""

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
