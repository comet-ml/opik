import logging
from contextlib import contextmanager
from typing import Any, Generator, Optional, Dict, List

from opik import datetime_helpers
from opik.api_objects import trace, opik_client, helpers
from opik import context_storage
from .. import base_track_decorator, error_info_collector

LOGGER = logging.getLogger(__name__)


@contextmanager
def start_as_current_trace(
    name: str,
    input: Optional[Dict[str, Any]] = None,
    output: Optional[Dict[str, Any]] = None,
    tags: Optional[List[str]] = None,
    metadata: Optional[Dict[str, Any]] = None,
    project_name: Optional[str] = None,
    thread_id: Optional[str] = None,
    flush: bool = False,
) -> Generator[trace.TraceData, Any, None]:
    """
    Starts a trace context manager to collect and manage tracing data during the
    execution of a code block. This function initializes a trace, allows for
    modifications within the context, and finishes the trace, sending data to the
    Opik tracing infrastructure.

    Args:
        name: The name of the trace for identification purposes.
        input: Optional input data associated with the trace.
        output: Optional output data expected or associated with the trace.
        tags: Optional list of string tags for labeling or describing the trace.
        metadata: Optional dictionary containing additional information about the
            trace.
        project_name: Optional name of the project under which the trace belongs.
        thread_id: Optional thread identifier to associate the trace with a
            specific thread.
        flush: A boolean indicating whether to flush the trace data immediately
            after finishing the trace context.

    Yields:
        Provides the initialized trace data for manipulation during execution in the context.
    """
    trace_data = trace.TraceData(
        id=helpers.generate_id(),
        start_time=datetime_helpers.local_timestamp(),
        name=name,
        input=input,
        output=output,
        metadata=metadata,
        tags=tags,
        project_name=project_name,
        thread_id=thread_id,
    )
    base_track_decorator.add_start_trace_candidate(
        trace_data=trace_data,
        opik_args_data=None,
        tracing_active=True,
    )

    try:
        yield trace_data
    except Exception as exception:
        LOGGER.error(
            "Error in user's script while executing trace context manager: %s",
            str(exception),
            exc_info=True,
        )
        trace_data.error_info = error_info_collector.collect(exception)
        trace_data.output = None
        raise
    finally:
        # save trace data at the end of the context manager
        client = opik_client.get_client_cached()
        client.trace(**trace_data.init_end_time().as_parameters)

        # Clean up trace from context
        opik_context_storage = context_storage.get_current_context_instance()
        opik_context_storage.pop_trace_data(ensure_id=trace_data.id)

        if flush:
            client.flush()
