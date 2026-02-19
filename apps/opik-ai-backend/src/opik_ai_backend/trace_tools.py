"""Clean implementations of trace analysis tools.

These functions are parameter-based and don't rely on closures.
They are used by the agent tools which provide closure-based wrappers.
"""

from typing import Any

from .opik_backend_client import OpikBackendClient

# TEST_PREFIX is used for development/evaluation only
# In production, traces with this prefix will raise an error
TEST_PREFIX = "eval-"


async def get_trace_data_impl(
    opik_client: OpikBackendClient, trace_id: str
) -> dict[str, Any]:
    """Return the trace data for the given trace.

    Args:
        opik_client: The Opik backend client instance.
        trace_id: The id of the trace to get data for.

    Returns:
        The trace data.

    Raises:
        ValueError: If trace_id is None.
    """
    if trace_id is None:
        raise ValueError("trace_id is required")
    if trace_id.startswith(TEST_PREFIX):
        raise ValueError(
            f"Test traces (prefix '{TEST_PREFIX}') are not supported in production"
        )

    trace_data = await opik_client.get_trace(trace_id)
    return {"trace": trace_data}


async def _get_spans_impl(
    opik_client: OpikBackendClient, trace_id: str, project_id: str
) -> dict[str, dict]:
    """Return the spans for the given trace.

    Args:
        opik_client: The Opik backend client instance.
        trace_id: The id of the trace to get spans for.
        project_id: The project ID (UUID).

    Returns:
        A dictionary mapping span IDs to span data.

    Raises:
        ValueError: If trace_id is None or no spans are found.
    """
    if trace_id is None:
        raise ValueError("trace_id is required")
    if trace_id.startswith(TEST_PREFIX):
        raise ValueError(
            f"Test traces (prefix '{TEST_PREFIX}') are not supported in production"
        )

    future_spans = {}

    spans = await opik_client.search_spans(
        project_id=project_id, trace_id=trace_id, truncate=False
    )
    if len(spans) == 0:
        raise ValueError(f"No spans found for trace {trace_id} in project {project_id}")

    for individual_span in spans:
        future_spans[individual_span["id"]] = individual_span

    return future_spans


async def get_spans_data_impl(
    opik_client: OpikBackendClient, trace_id: str, project_id: str
) -> list[dict[str, Any]]:
    """Return the list of spans for the given trace. Span input, output, and metadata are excluded as they can be quite large but can be requested later if needed using get_span_details.

    Args:
        opik_client: The Opik backend client instance.
        trace_id: The id of the trace to get spans for.
        project_id: The project ID (UUID).

    Returns:
        The spans data without input, output, and metadata fields. These can be retrieved on-demand using get_span_details.
    """
    spans = await _get_spans_impl(opik_client, trace_id, project_id)
    span_without_large_fields = []
    for span in spans.values():
        single_span = span.copy()
        # Remove potentially large fields
        single_span.pop("input", None)
        single_span.pop("output", None)
        single_span.pop("metadata", None)
        span_without_large_fields.append(single_span)
    return span_without_large_fields


async def get_span_details_impl(
    opik_client: OpikBackendClient,
    span_id: str,
    include_input: bool = False,
    include_output: bool = False,
    include_metadata: bool = False,
) -> dict[str, Any]:
    """Return selected details of a span in a single call.

    This combined tool reduces the number of tool calls needed by allowing
    the agent to fetch multiple fields (input, output, metadata) at once.

    Args:
        opik_client: The Opik backend client instance.
        span_id: The id of the span to get details for.
        include_input: Whether to include the span's input field.
        include_output: Whether to include the span's output field.
        include_metadata: Whether to include the span's metadata field.

    Returns:
        A dictionary containing the requested fields. Always includes span_id.
    """
    span = await opik_client.get_span(span_id)

    result: dict[str, Any] = {"span_id": span_id}

    if include_input:
        result["input"] = span.get("input", None)
    if include_output:
        result["output"] = span.get("output", None)
    if include_metadata:
        result["metadata"] = span.get("metadata", None)

    return result
