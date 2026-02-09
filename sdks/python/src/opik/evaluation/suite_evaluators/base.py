from abc import abstractmethod
from typing import Any, TypeVar

from opik.evaluation.metrics import base_metric

ConfigT = TypeVar("ConfigT")


class BaseSuiteEvaluator(base_metric.BaseMetric):
    """
    Base class for suite evaluators.

    Suite evaluators are designed for use with evaluation suites and provide
    serialization support via `to_config()` and `from_config()` methods for
    compatibility with the backend's online evaluation system.

    Subclasses must implement:
        - `to_config()`: Serialize the evaluator to a configuration object
        - `from_config()`: Create an evaluator instance from a configuration
    """

    @abstractmethod
    def to_config(self) -> Any:
        """
        Serialize the evaluator configuration.

        Returns:
            A configuration object that can be used to reconstruct this evaluator.
        """
        ...

    @classmethod
    @abstractmethod
    def from_config(
        cls,
        config: Any,
        name: str,
        track: bool = True,
        project_name: str | None = None,
    ) -> "BaseSuiteEvaluator":
        """
        Create an evaluator instance from a configuration.

        Args:
            config: The configuration object.
            name: The name of the evaluator.
            track: Whether to track the evaluator.
            project_name: Optional project name for tracking.

        Returns:
            A new evaluator instance.
        """
        ...
