"""Exceptions raised by the core optimization runtime."""

from __future__ import annotations


class ScoringFailedError(RuntimeError):
    """Raised when the objective metric failed to score a run's evaluation items.

    This surfaces the OPIK-7029 "silent COMPLETED" gap: when the LLM-as-judge (or
    any objective metric) raises or returns unparsable output for enough of the
    evaluation items, the run should fail loudly (ERROR) instead of completing with
    a misleading ``0.0`` score.

    Carries the failed/total counts so downstream error classification can build a
    precise user-facing message ("... failed on N of M items ...").
    """

    def __init__(
        self,
        failed: int,
        total: int,
        *,
        objective_metric_name: str | None = None,
        message: str | None = None,
    ) -> None:
        self.failed = failed
        self.total = total
        self.objective_metric_name = objective_metric_name

        if message is None:
            metric_label = (
                f" '{objective_metric_name}'" if objective_metric_name else ""
            )
            message = (
                f"The objective metric{metric_label} failed to score "
                f"{failed} of {total} evaluation item(s). The judge model likely "
                f"failed or returned invalid output. Check the metric and its model, "
                f"then run it again."
            )
        super().__init__(message)
