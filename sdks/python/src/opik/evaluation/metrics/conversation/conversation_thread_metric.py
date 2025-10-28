from typing import Any, List, Union

from .. import score_result
from ..base_metric import BaseMetric
from . import types


class ConversationThreadMetric(BaseMetric):
    """
    Abstract base class for metrics operating on full conversation threads.

    Implementations receive a conversation represented as a list of message
    dictionaries, where each message contains a ``role`` (``"user"`` or
    ``"assistant"``) and the associated ``content`` string. Subclasses should
    implement :meth:`score` and optionally :meth:`ascore` to return one or more
    :class:`~opik.evaluation.metrics.score_result.ScoreResult` objects.

    Example
    -------
    >>> from typing import Any
    >>> from opik.evaluation.metrics.conversation import (
    ...     ConversationThreadMetric,
    ...     types,
    ... )
    >>> from opik.evaluation.metrics import score_result
    >>>
    >>> class ConversationLengthMetric(ConversationThreadMetric):
    ...     def score(
    ...         self, conversation: types.Conversation, **kwargs: Any
    ...     ) -> score_result.ScoreResult:
    ...         num_turns = sum(1 for msg in conversation if msg["role"] == "assistant")
    ...         return score_result.ScoreResult(
    ...             name=self.name,
    ...             value=num_turns,
    ...             reason=f"Conversation has {num_turns} assistant turns",
    ...         )
    """

    def score(
        self, conversation: types.Conversation, **kwargs: Any
    ) -> Union[score_result.ScoreResult, List[score_result.ScoreResult]]:
        """
        Evaluate a conversation and return the resulting scores.

        Subclasses must override this method.
        """

        raise NotImplementedError("Please implement score() in subclasses.")

    async def ascore(
        self, conversation: types.Conversation, **kwargs: Any
    ) -> Union[score_result.ScoreResult, List[score_result.ScoreResult]]:
        """
        Asynchronous version of :meth:`score`.

        The default implementation simply raises ``NotImplementedError``.  Async
        metrics can override this method to provide a native async implementation.
        """

        raise NotImplementedError("Please implement ascore() in subclasses.")

