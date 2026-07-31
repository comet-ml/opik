"""Exceptions raised by the core optimization runtime."""

from __future__ import annotations

from opik import exceptions as opik_exceptions


class EmptyLLMResponseError(opik_exceptions.OpikException):
    """Raised when an LLM returned no content where the caller required text.

    A content-filtered or tool-call-only completion carries no message content.
    Callers that must hand the text to another component cannot substitute a
    placeholder — stringifying ``None`` would pass the literal instruction
    "None" downstream — so they fail with this instead (OPIK-7521).

    Carries the model and the calling purpose so the message names both.
    """

    def __init__(self, *, model: str, purpose: str) -> None:
        self.model = model
        self.purpose = purpose
        super().__init__(
            f"{purpose} received an empty response from {model}: the model "
            "returned no content (content filter, or a tool-call-only "
            "completion), so there is nothing to use."
        )


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
