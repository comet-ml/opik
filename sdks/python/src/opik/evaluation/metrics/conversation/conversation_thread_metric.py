from typing import Union, List, Any

from . import types
from .. import base_metric, score_result


class ConversationThreadMetric(base_metric.BaseMetric):
    """
    Base class for metrics that evaluate an entire conversation thread.

    Subclasses receive the full sequence of conversation turns as a list of
    dictionaries shaped like ``{"role": str, "content": str}`` and should return a
    ``ScoreResult`` or list of ``ScoreResult`` objects. Implementors must override
    ``score`` (and optionally ``ascore``) to provide the actual evaluation logic.

    Args:
        name: Display name for the metric. Defaults to ``"conversation_metric"`` when
            defined in subclasses.
        track: Whether results should be automatically tracked in Opik.
        project_name: Optional project slug used for tracking.

    Example:
        >>> from opik.evaluation.metrics.conversation import conversation_thread_metric
        >>> from opik.evaluation.metrics import score_result
        >>> class DummyConversationMetric(conversation_thread_metric.ConversationThreadMetric):
        ...     def score(self, conversation, **_):
        ...         return score_result.ScoreResult(name=self.name, value=float(len(conversation) > 0))
        >>> metric = DummyConversationMetric(name="dummy_conversation_metric")
        >>> dialogue = [{"role": "user", "content": "Hi"}, {"role": "assistant", "content": "Hello"}]
        >>> metric.score(dialogue).value
        1.0
    """

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
