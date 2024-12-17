import abc
from typing import Any, Union, List

from ..metrics import score_result
from opik import track as track_decorator


class BaseMetric(abc.ABC):
    """
    Abstract base class for all metrics. When creating a new metric, you should inherit
    from this class and implement the abstract methods.

    Args:
        name: The name of the metric.
        track: Whether to track the metric. Defaults to True.

    Example:
        >>> from opik.evaluation.metrics import base_metric, score_result
        >>>
        >>> class MyCustomMetric(base_metric.BaseMetric):
        >>>     def __init__(self, name: str, track: bool = True):
        >>>         self.name = name
        >>>         self.track = track
        >>>
        >>>     def score(self, input: str, output: str, **ignored_kwargs: Any):
        >>>         # Add you logic here
        >>>
        >>>         return score_result.ScoreResult(
        >>>             value=0,
        >>>             name=self.name,
        >>>             reason="Optional reason for the score"
        >>>         )
    """

    def __init__(self, name: str, track: bool = True) -> None:
        self.name = name
        self.track = track

        if track:
            self.score = track_decorator(name=self.name)(self.score)  # type: ignore
            self.ascore = track_decorator(name=self.name)(self.ascore)  # type: ignore

    @abc.abstractmethod
    def score(
        self, *args: Any, **kwargs: Any
    ) -> Union[score_result.ScoreResult, List[score_result.ScoreResult]]:
        """
        Public method that can be called independently.
        """
        raise NotImplementedError()

    async def ascore(
        self, *args: Any, **kwargs: Any
    ) -> Union[score_result.ScoreResult, List[score_result.ScoreResult]]:
        """
        Async public method that can be called independently.
        """
        return self.score(*args, **kwargs)
