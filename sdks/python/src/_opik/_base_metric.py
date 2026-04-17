"""Lightweight BaseMetric ABC with no heavy dependencies."""

import abc
from typing import Any, List, Optional, Union

from . import _score_result


class BaseMetric(abc.ABC):
    """Abstract base class for all metrics.

    Subclass this and implement :meth:`score` to create a custom metric.
    The lightweight version carries no tracking or configuration overhead,
    making it suitable for contexts that only need the metric interface.

    Args:
        name: Display name for the metric.  Defaults to the class name.
        track: Whether the metric should be tracked by Opik.
        project_name: Optional project to associate tracked results with.
    """

    def __init__(
        self,
        name: Optional[str] = None,
        track: bool = True,
        project_name: Optional[str] = None,
    ) -> None:
        self.name = name if name is not None else self.__class__.__name__
        self.track = track
        self.project_name = project_name

    @abc.abstractmethod
    def score(
        self, *args: Any, **kwargs: Any
    ) -> Union[_score_result.ScoreResult, List[_score_result.ScoreResult]]:
        """Compute the metric score. Must be implemented by subclasses."""
        raise NotImplementedError()

    async def ascore(
        self, *args: Any, **kwargs: Any
    ) -> Union[_score_result.ScoreResult, List[_score_result.ScoreResult]]:
        """Async variant of :meth:`score`. Defaults to calling ``score``."""
        return self.score(*args, **kwargs)
