from typing import Union, List, Any

from . import types
from .. import base_metric, score_result


class ConversationThreadMetric(base_metric.BaseMetric):
    """
    Abstract base class for all conversation thread metrics. When creating a custom
    conversation metric, you should inherit from this class and implement the abstract methods.

    Conversation metrics are designed to evaluate multi-turn conversations rather than
    single input-output pairs. They accept a conversation as a list of message dictionaries,
    where each message has a 'role' (either 'user' or 'assistant') and 'content'.

    Args:
        name: The name of the metric. If not provided, uses the class name as default.
        track: Whether to track the metric. Defaults to True.
        project_name: Optional project name to track the metric in for the cases when
            there is no parent span/trace to inherit project name from.

    Example:
        >>> from opik.evaluation.metrics.conversation import conversation_thread_metric, types
        >>> from opik.evaluation.metrics import score_result
        >>> from typing import Any
        >>>
        >>> class ConversationLengthMetric(conversation_thread_metric.ConversationThreadMetric):
        >>>     def __init__(self, name: str = "conversation_length_score"):
        >>>         super().__init__(name)
        >>>
        >>>     def score(self, conversation: types.Conversation, **kwargs: Any):
        >>>         num_turns = sum(1 for msg in conversation if msg["role"] == "assistant")
        >>>         return score_result.ScoreResult(
        >>>             name=self.name,
        >>>             value=num_turns,
        >>>             reason=f"Conversation has {num_turns} turns"
        >>>         )
    """

    def score(
        self, conversation: types.Conversation, **kwargs: Any
    ) -> Union[score_result.ScoreResult, List[score_result.ScoreResult]]:
        """
        Evaluate a conversation and return a score.

        Args:
            conversation: A list of conversation messages. Each message is a dictionary
                with 'role' (either 'user' or 'assistant') and 'content' (the message text).
            **kwargs: Additional keyword arguments that may be used by specific metric implementations.

        Returns:
            A ScoreResult object or list of ScoreResult objects containing the evaluation score,
            metric name, and optional reasoning.
        """
        raise NotImplementedError(
            "Please use concrete metric classes instead of this one."
        )

    async def ascore(
        self, conversation: types.Conversation, **kwargs: Any
    ) -> Union[score_result.ScoreResult, List[score_result.ScoreResult]]:
        """
        Asynchronously evaluate a conversation and return a score.

        This is the async version of the score method. By default, it calls the
        synchronous score method, but can be overridden for true async implementations.

        Args:
            conversation: A list of conversation messages. Each message is a dictionary
                with 'role' (either 'user' or 'assistant') and 'content' (the message text).
            **kwargs: Additional keyword arguments that may be used by specific metric implementations.

        Returns:
            A ScoreResult object or list of ScoreResult objects containing the evaluation score,
            metric name, and optional reasoning.
        """
        raise NotImplementedError(
            "Please use concrete metric classes instead of this one."
        )
