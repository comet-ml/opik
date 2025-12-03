"""Custom exceptions for the eval app."""

import opik.exceptions as opik_exceptions


class EvalAppError(opik_exceptions.OpikException):
    """Base exception for eval app related errors."""

    pass


class UnknownMetricError(EvalAppError):
    """Raised when an unknown metric is requested."""

    def __init__(self, metric_name: str):
        self.metric_name = metric_name
        super().__init__(self._get_error_message())

    def _get_error_message(self) -> str:
        return f"Unknown metric: {self.metric_name}. Use GET /api/v1/evaluation/metrics to see available metrics."


class MetricInstantiationError(EvalAppError):
    """Raised when a metric cannot be instantiated."""

    def __init__(self, metric_name: str, error: str):
        self.metric_name = metric_name
        self.error = error
        super().__init__(self._get_error_message())

    def _get_error_message(self) -> str:
        return f"Failed to instantiate metric '{self.metric_name}': {self.error}"


class TraceNotFoundError(EvalAppError):
    """Raised when a trace cannot be found."""

    def __init__(self, trace_id: str):
        self.trace_id = trace_id
        super().__init__(self._get_error_message())

    def _get_error_message(self) -> str:
        return f"Trace not found: {self.trace_id}"


class InvalidFieldMappingError(EvalAppError):
    """Raised when field mapping is invalid."""

    def __init__(self, field: str, error: str):
        self.field = field
        self.error = error
        super().__init__(self._get_error_message())

    def _get_error_message(self) -> str:
        return f"Invalid field mapping for '{self.field}': {self.error}"


class EvaluationError(EvalAppError):
    """Raised when evaluation fails."""

    def __init__(self, metric_name: str, error: str):
        self.metric_name = metric_name
        self.error = error
        super().__init__(self._get_error_message())

    def _get_error_message(self) -> str:
        return f"Evaluation failed for metric '{self.metric_name}': {self.error}"

