import sys
from typing import Any, List, Optional, Type, Union

import opik
import opik.config as opik_config
import _opik._base_metric as _opik_base_metric
from ... import analytics
from ..metrics import score_result


def _is_opik_metric(metric_class: Type["BaseMetric"]) -> bool:
    """
    True only for a class Opik actually ships. `__module__` alone would not do: a
    subclass can set it to anything, and a name the user chose would then be
    reported as one of ours - which is the one thing these payloads must never
    carry. Looking the class back up in the module it claims closes that.
    """
    if not metric_class.__module__.startswith("opik."):
        return False

    module = sys.modules.get(metric_class.__module__)
    return getattr(module, metric_class.__name__, None) is metric_class


def _track_metric_creation(metric_class: Type["BaseMetric"]) -> None:
    # A user-defined metric class can be named anything, so only the names of
    # Opik's own metrics are reported.
    name = metric_class.__name__ if _is_opik_metric(metric_class) else "custom"

    analytics.track_event("evaluation", "metric_created", metric=name)


class BaseMetric(_opik_base_metric.BaseMetric):
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
        # First, before any of the setup below: a metric whose construction goes on
        # to fail was still a metric the user reached for, and reporting is meant to
        # count that. Only needs the class.
        _track_metric_creation(type(self))

        super().__init__(name=name, track=track, project_name=project_name)

        config = opik_config.OpikConfig()

        if not track and project_name is not None:
            raise ValueError("project_name can be set only when `track` is set to True")

        if track and config.check_for_known_misconfigurations() is False:
            track_decorator = opik.track(name=self.name, project_name=project_name)
            self.score = track_decorator(self.score)  # type: ignore
            self.ascore = track_decorator(self.ascore)  # type: ignore

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
