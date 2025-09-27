from __future__ import annotations

from typing import Any, Optional

from ...heuristics.rouge import ROUGE
from ..reference_turn_metric import ConversationReferenceMetric


class RougeCMetric(ConversationReferenceMetric):
    """Conversation-level ROUGE aggregator built on top of :class:`ROUGE`."""

    def __init__(
        self,
        rouge_metric: Optional[ROUGE] = None,
        target_role: str = "assistant",
        missing_turn_penalty: float = 0.0,
        name: str = "rouge_c_metric",
        track: bool = True,
        project_name: Optional[str] = None,
        **rouge_kwargs: Any,
    ) -> None:
        turn_metric = rouge_metric or ROUGE(track=False, **rouge_kwargs)
        super().__init__(
            turn_metric=turn_metric,
            target_role=target_role,
            missing_turn_penalty=missing_turn_penalty,
            name=name,
            track=track,
            project_name=project_name,
        )
