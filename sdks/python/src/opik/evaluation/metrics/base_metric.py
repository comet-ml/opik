import abc
from typing import Any, List, Union, Optional

import opik
import opik.config as opik_config
from ..metrics import score_result


class BaseMetric(abc.ABC):
    """
    Abstract base class for all metrics. When creating a new metric, you should inherit
    from this class and implement the abstract methods.

    Args:
        name: The name of the metric. If not provided, uses the class name as default.
        track: Whether to track the metric. Defaults to True.
        project_name: Optional project name to track the metric in for the cases when
            there is no parent span/trace to inherit project name from.

    Example:
        >>> from opik.evaluation.metrics import base_metric, score_result
        >>>
        >>> class MyCustomMetric(base_metric.BaseMetric):
        >>>     def __init__(self, name: str, track: bool = True):
        >>>         super().__init__(name=name, track=track)
        >>>
        >>>     def score(self, input: str, output: str, **ignored_kwargs: Any):
        >>>         # Add your logic here
        >>>
        >>>         return score_result.ScoreResult(
        >>>             value=0,
        >>>             name=self.name,
        >>>             reason="Optional reason for the score"
        >>>         )
    """

    def __init__(
        self,
        name: Optional[str] = None,
        track: bool = True,
        project_name: Optional[str] = None,
    ) -> None:
        self.name = name if name is not None else self.__class__.__name__
        self.track = track

        config = opik_config.OpikConfig()

        if not track and project_name is not None:
            raise ValueError("project_name can be set only when `track` is set to True")

        if track and config.check_for_known_misconfigurations() is False:
            track_decorator = opik.track(name=self.name, project_name=project_name)
            self.score = track_decorator(self.score)  # type: ignore
            self.ascore = track_decorator(self.ascore)  # type: ignore

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
