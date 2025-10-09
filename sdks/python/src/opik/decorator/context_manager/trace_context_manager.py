import logging
from typing import Any, Generator, Optional, Dict, List

from opik import datetime_helpers
from opik.api_objects import trace, opik_client, helpers
from .. import base_track_decorator

LOGGER = logging.getLogger(__name__)


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
        raise

    # save trace data at the end of the context manager
    client = opik_client.get_client_cached()
    client.trace(**trace_data.init_end_time().as_parameters)

    if flush:
        client.flush()
