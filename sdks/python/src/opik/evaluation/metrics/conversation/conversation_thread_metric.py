from typing import Union, List, Any

from . import types
from .. import base_metric, score_result


class ConversationThreadMetric(base_metric.BaseMetric):
    """Abstract base class for all conversation thread metrics."""

    def score(
        self, conversation: types.Conversation, **kwargs: Any
    ) -> Union[score_result.ScoreResult, List[score_result.ScoreResult]]:
        raise NotImplementedError(
            "Please use concrete metric classes instead of this one."
        )

    async def ascore(
        self, conversation: types.Conversation, **kwargs: Any
    ) -> Union[score_result.ScoreResult, List[score_result.ScoreResult]]:
        """
        Async public method that can be called independently.
        """
        raise NotImplementedError(
            "Please use concrete metric classes instead of this one."
        )
