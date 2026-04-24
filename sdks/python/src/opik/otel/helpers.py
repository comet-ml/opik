from typing import Dict, Optional, TYPE_CHECKING

from opik.otel.types import OpikDistributedTraceAttributes

if TYPE_CHECKING:
    from opentelemetry import trace


def extract_opik_distributed_trace_attributes(
    http_headers: Dict[str, str],
) -> Optional[OpikDistributedTraceAttributes]:
    """
    Extracts Opik distributed trace attributes from HTTP headers.

    This function retrieves distributed tracing attributes from the provided
    HTTP headers using the keys `opik_trace_id` and optionally
    `opik_parent_span_id`. If `opik_trace_id` is not present in the headers,
    the function returns None.

    Args:
        http_headers: A dictionary containing HTTP headers as
            keys and their corresponding values.

    Returns:
        An object containing the extracted `opik_trace_id` and `opik_parent_span_id` if available.
        Returns None if `opik_trace_id` is not found.
    """
    if "opik_trace_id" in http_headers:
        return OpikDistributedTraceAttributes(
            opik_trace_id=http_headers["opik_trace_id"],
            opik_parent_span_id=http_headers.get("opik_parent_span_id", None),
        )
    return None


def attach_to_parent(span: "trace.Span", http_headers: Dict[str, str]) -> bool:
    """
    Attaches a distributed trace parent to the provided span, using extracted
    headers to gather the necessary trace-related attributes.

    This function parses the provided HTTP headers to extract OPIK distributed trace
    headers. If valid headers are found, it converts them into OpenTelemetry span
    attributes and attaches them to the span. If no valid headers are found, the
    function returns False.

    Args:
        span: A `opentelemetry.trace.Span` object to which the distributed trace parent will be
            attached.
        http_headers: A dictionary of HTTP headers where distributed trace headers
            may be present.

    Returns:
        True if the distributed trace headers were successfully extracted
            and attached, otherwise False.
    """
    opik_distributed_trace_headers = extract_opik_distributed_trace_attributes(
        http_headers
    )
    if opik_distributed_trace_headers is None:
        return False

    attributes = opik_distributed_trace_headers.as_attributes()
    span.set_attributes(attributes)
    return True
