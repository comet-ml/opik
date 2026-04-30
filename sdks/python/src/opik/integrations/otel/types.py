from typing import Optional, Dict

from .attributes import OPIK_PARENT_SPAN_ID, OPIK_TRACE_ID


class OpikDistributedTraceAttributes:
    """Represents distributed trace attributes for the OPIK tracing system.

    This class provides a structured way to handle distributed trace attributes,
    specifically trace ID and parent span ID. It includes functionality to convert
    these attributes into a dictionary format suitable for integration with
    OpenTelemetry.
    """

    def __init__(self, opik_trace_id: str, opik_parent_span_id: Optional[str]):
        """
        Initializes an instance of the class with tracing parameters.

        Args:
            opik_trace_id: The unique identifier for the trace.
            opik_parent_span_id: The identifier for the parent span in the trace,
                or None if this is the root span.
        """
        self._opik_trace_id = opik_trace_id
        self._opik_parent_span_id = opik_parent_span_id

    def as_attributes(self) -> Dict[str, str]:
        """
        Converts the distributed trace attributes into a dictionary suitable for OpenTelemetry.

        Returns:
            A dictionary containing the trace ID and parent span ID (if applicable).
        """
        res = {
            OPIK_TRACE_ID: self._opik_trace_id,
        }
        if self._opik_parent_span_id is not None:
            res[OPIK_PARENT_SPAN_ID] = self._opik_parent_span_id
        return res
