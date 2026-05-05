import logging
from typing import Dict, Optional, TYPE_CHECKING

from opik import id_helpers
from opik.integrations.otel import types as otel_types
from opik.integrations.otel import attributes as otel_attributes

if TYPE_CHECKING:
    from opentelemetry import trace

LOGGER = logging.getLogger(__name__)

OPIK_TRACE_ID_HEADER = "opik_trace_id"
OPIK_PARENT_SPAN_ID_HEADER = "opik_parent_span_id"


def _get_header(http_headers: Dict[str, str], name: str) -> Optional[str]:
    target = name.lower()
    for key, value in http_headers.items():
        if isinstance(key, str) and key.lower() == target:
            return value
    return None


def extract_opik_distributed_trace_attributes(
    http_headers: Dict[str, str],
) -> Optional[otel_types.OpikDistributedTraceAttributes]:
    """
    Extracts Opik distributed trace attributes from HTTP headers.

    This function retrieves distributed tracing attributes from the provided
    HTTP headers using the keys `opik_trace_id` and optionally
    `opik_parent_span_id` (case-insensitive). Values are trimmed; if
    `opik_trace_id` is missing, blank, or not a valid UUID the function
    returns None (and logs a warning when invalid, or when
    `opik_parent_span_id` was provided without a `opik_trace_id`). A blank
    or non-UUID `opik_parent_span_id` is dropped with a warning while the
    valid `opik_trace_id` is still returned.

    Args:
        http_headers: A dictionary containing HTTP headers as
            keys and their corresponding values.

    Returns:
        An object containing the extracted `opik_trace_id` and `opik_parent_span_id` if available.
        Returns None if `opik_trace_id` is not found or is not a valid UUID.
    """
    raw_trace_id = _get_header(http_headers, OPIK_TRACE_ID_HEADER)
    trace_id = raw_trace_id.strip() if isinstance(raw_trace_id, str) else None

    raw_parent_span_id = _get_header(http_headers, OPIK_PARENT_SPAN_ID_HEADER)
    parent_span_id = (
        raw_parent_span_id.strip() if isinstance(raw_parent_span_id, str) else None
    )

    if not trace_id:
        if parent_span_id:
            LOGGER.warning(
                "Opik distributed trace header '%s' is missing while '%s' "
                "is provided; skipping distributed trace processing.",
                OPIK_TRACE_ID_HEADER,
                OPIK_PARENT_SPAN_ID_HEADER,
            )
        return None
    if not id_helpers.is_valid_uuid_v7(trace_id):
        LOGGER.warning(
            "Opik distributed trace header '%s' is not a valid UUIDv7; "
            "skipping distributed trace processing.",
            OPIK_TRACE_ID_HEADER,
        )
        return None

    if not parent_span_id:
        parent_span_id = None
    elif not id_helpers.is_valid_uuid_v7(parent_span_id):
        LOGGER.warning(
            "Opik distributed trace header '%s' is not a valid UUIDv7; "
            "ignoring parent span id.",
            OPIK_PARENT_SPAN_ID_HEADER,
        )
        parent_span_id = None

    return otel_types.OpikDistributedTraceAttributes(
        opik_trace_id=trace_id,
        opik_parent_span_id=parent_span_id,
    )


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
    # Mint a stable opik.span_id for this boundary span so descendants picked up by
    # OpikSpanProcessor can chain through it (their opik.parent_span_id will reference
    # this value, and the backend uses opik.span_id verbatim — see OpenTelemetryMapper).
    attributes[otel_attributes.OPIK_SPAN_ID] = id_helpers.generate_id()
    span.set_attributes(attributes)
    return True
