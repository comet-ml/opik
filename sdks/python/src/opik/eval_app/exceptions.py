"""Custom exceptions for the eval app."""

from opik.exceptions import OpikException


class EvalAppError(OpikException):
    """Base exception for eval app errors."""

    pass


class UnknownMetricError(EvalAppError):
    """Raised when a requested metric is not found in the registry."""

    def __init__(self, metric_name: str) -> None:
        self.metric_name = metric_name
        super().__init__(
            f"Unknown metric: {metric_name}. "
            f"Use GET /api/v1/evaluation/metrics to see available metrics."
        )


class MetricInstantiationError(EvalAppError):
    """Raised when a metric cannot be instantiated with the given arguments."""

    def __init__(self, metric_name: str, reason: str) -> None:
        self.metric_name = metric_name
        self.reason = reason
        super().__init__(f"Failed to instantiate metric {metric_name}: {reason}")


class TraceNotFoundError(EvalAppError):
    """Raised when the requested trace is not found."""

    def __init__(self, trace_id: str) -> None:
        self.trace_id = trace_id
        super().__init__(f"Trace not found: {trace_id}")


class InvalidFieldMappingError(EvalAppError):
    """Raised when field mapping references invalid trace fields."""

    def __init__(self, field_path: str, reason: str) -> None:
        self.field_path = field_path
        self.reason = reason
        super().__init__(f"Invalid field mapping '{field_path}': {reason}")


class EvaluationError(EvalAppError):
    """Raised when evaluation fails."""

    def __init__(self, reason: str) -> None:
        self.reason = reason
        super().__init__(f"Evaluation failed: {reason}")
