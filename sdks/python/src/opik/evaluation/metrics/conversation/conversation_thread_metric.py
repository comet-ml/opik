from typing import Union, List, Any

from opik.api_objects.conversation import conversation_thread
from .. import base_metric, score_result


class ConversationThreadMetric(base_metric.BaseMetric):
    """Abstract base class for all conversation thread metrics."""

    def score(
        self, thread: conversation_thread.ConversationThread, **kwargs: Any
    ) -> Union[score_result.ScoreResult, List[score_result.ScoreResult]]:
        raise NotImplementedError(
            "Please use concrete metric classes instead of this one."
        )
