from __future__ import annotations

from typing import Any, Optional

from ...heuristics.meteor import METEOR
from ..reference_turn_metric import ConversationReferenceMetric


class MeteorCMetric(ConversationReferenceMetric):
    """Aggregates METEOR scores over assistant turns."""

    def __init__(
        self,
        meteor_metric: Optional[METEOR] = None,
        target_role: str = "assistant",
        missing_turn_penalty: float = 0.0,
        name: str = "meteor_c_metric",
        track: bool = True,
        project_name: Optional[str] = None,
        **meteor_kwargs: Any,
    ) -> None:
        turn_metric = meteor_metric or METEOR(track=False, **meteor_kwargs)
        super().__init__(
            turn_metric=turn_metric,
            target_role=target_role,
            missing_turn_penalty=missing_turn_penalty,
            name=name,
            track=track,
            project_name=project_name,
        )
