from typing import Any

from opik.evaluation.metrics import base_metric, score_result
from opik.message_processing.emulation import models as emulation_models
import opik.exceptions


class SpanDuration(base_metric.BaseMetric):
    """
    A metric that calculates the total duration of a span in seconds.

    Args:
        name: The name of the metric. Defaults to "total_span_duration".
        track: Whether to track the metric. Defaults to True.
        project_name: The name of the project to track the metric in. Defaults to None.
    """

    def __init__(
        self,
        name: str = "total_span_duration",
        track: bool = True,
        project_name: str | None = None,
    ) -> None:
        super().__init__(name=name, track=track, project_name=project_name)

    def score(
        self, task_span: emulation_models.SpanModel, **_: Any
    ) -> score_result.ScoreResult:
        if task_span.end_time is None or task_span.start_time is None:
            raise opik.exceptions.MetricComputationError(
                "Span end time or start time is not set"
            )

        duration = (task_span.end_time - task_span.start_time).total_seconds()

        return score_result.ScoreResult(value=duration, name=self.name)
