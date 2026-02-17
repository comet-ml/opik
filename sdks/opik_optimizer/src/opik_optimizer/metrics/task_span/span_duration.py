from typing import Any

from opik.evaluation.metrics import base_metric, score_result
from opik.message_processing.emulation import models as emulation_models
import opik.exceptions


class SpanDuration(base_metric.BaseMetric):
    """
    A metric that calculates the total duration of a span in seconds.

    Args:
        name: The name of the metric. Defaults to "total_span_duration".
        target: Optional target duration (seconds) used to normalize output into
            a score in (0, 1], where higher is better. This is the recommended mode
            for `MultiMetricObjective`, where duration should be on a bounded scale.
            When None, returns raw seconds.
        invert: Controls optimization direction when `target` is provided.
            - True (default): lower duration -> higher score.
            - False: higher duration -> higher score.
        target_duration_seconds: Backward-compatible alias for `target`.
        track: Whether to track the metric. Defaults to True.
        project_name: The name of the project to track the metric in. Defaults to None.
    """

    def __init__(
        self,
        name: str = "total_span_duration",
        target: float | None = None,
        invert: bool = True,
        target_duration_seconds: float | None = None,
        track: bool = True,
        project_name: str | None = None,
    ) -> None:
        super().__init__(name=name, track=track, project_name=project_name)
        if target is not None and target_duration_seconds is not None:
            raise ValueError(
                "Received both `target` and `target_duration_seconds`; use only `target`."
            )

        resolved_target = target if target is not None else target_duration_seconds
        if resolved_target is not None and float(resolved_target) <= 0:
            raise ValueError("SpanDuration `target` must be > 0 when provided.")

        self.target_duration_seconds = (
            None if resolved_target is None else float(resolved_target)
        )
        self.invert = bool(invert)

    def score(
        self, task_span: emulation_models.SpanModel | None = None, **_: Any
    ) -> score_result.ScoreResult:
        if task_span is None:
            return score_result.ScoreResult(
                name=self.name,
                value=0.0,
                reason=(
                    "SpanDuration could not compute because `task_span` was not provided "
                    "by the evaluation runtime."
                ),
                scoring_failed=True,
            )

        if task_span.end_time is None or task_span.start_time is None:
            missing_fields = []
            if task_span.start_time is None:
                missing_fields.append("start_time")
            if task_span.end_time is None:
                missing_fields.append("end_time")
            raise opik.exceptions.MetricComputationError(
                "SpanDuration cannot compute duration because "
                f"{', '.join(missing_fields)} is missing "
                f"(span_id={task_span.id}, span_name={task_span.name})."
            )

        duration = (task_span.end_time - task_span.start_time).total_seconds()
        if self.target_duration_seconds is None:
            return score_result.ScoreResult(value=duration, name=self.name)

        normalized = duration / self.target_duration_seconds
        if self.invert:
            value = 1.0 / (1.0 + normalized)
        else:
            value = normalized / (1.0 + normalized)

        direction = "lower-is-better" if self.invert else "higher-is-better"
        return score_result.ScoreResult(
            name=self.name,
            value=value,
            reason=(
                f"Total span duration={duration:.2f}s -> score={value:.3f} "
                f"(target={self.target_duration_seconds:.2f}s, direction={direction})"
            ),
            metadata={
                "raw_total_span_duration_seconds": duration,
                "target_duration_seconds": self.target_duration_seconds,
                "invert": self.invert,
            },
        )
