import logging
from contextlib import contextmanager
from typing import Generator, Any

from opik import context_storage
from opik.api_objects import span, trace
from opik.types import DistributedTraceHeadersDict

LOGGER = logging.getLogger(__name__)


@contextmanager
def distributed_headers(
    headers: DistributedTraceHeadersDict,
) -> Generator[None, Any, None]:
    """
    Context manager for managing distributed tracing headers.

    This context manager is used to handle distributed tracing headers in a
    structured manner. It ensures root span creation, error logging during user
    script execution, and cleanup of root span data after use.

    Args:
        headers: Distributed tracing headers used for root span creation.
    """
    if not headers or not headers.get("opik_trace_id"):
        LOGGER.warning(
            "Empty distributed headers provided. Skipping setting distributed headers."
        )
        yield
        return

    opik_context_storage = context_storage.get_current_context_instance()

    trace_id = headers["opik_trace_id"]
    trace_data = trace.TraceData(id=trace_id)
    opik_context_storage.set_trace_data(trace_data)

    parent_span_id = headers.get("opik_parent_span_id")
    if parent_span_id is not None:
        # this is a fake span that imitates a span created somewhere,
        # so using opik_parent_span_id as span_id
        span_data = span.SpanData(
            id=parent_span_id,
            trace_id=trace_id,
        )
        context_storage.add_span_data(span_data)
    else:
        span_data = None

    try:
        yield
    except Exception as exception:
        LOGGER.error(
            "Error in user's script while executing distributed headers context manager: %s",
            str(exception),
            exc_info=True,
        )

        raise
    finally:
        # Clean up fake trace/span data from context
        opik_context_storage.pop_trace_data(ensure_id=trace_id)
        if span_data is not None:
            opik_context_storage.pop_span_data(ensure_id=span_data.id)
