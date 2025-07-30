import contextlib
from typing import Iterator, Optional

import opik.context_storage as context_storage
from opik.api_objects import trace, opik_client
from opik.decorator import error_info_collector
from opik.types import ErrorInfoDict


@contextlib.contextmanager
def evaluate_llm_conversation_context(
    trace_data: trace.TraceData,
    client: opik_client.Opik,
) -> Iterator[None]:
    error_info: Optional[ErrorInfoDict] = None
    try:
        context_storage.set_trace_data(trace_data)
        yield
    except Exception as exception:
        error_info = error_info_collector.collect(exception)
        raise
    finally:
        trace_data = context_storage.pop_trace_data()  # type: ignore

        assert trace_data is not None

        if error_info is not None:
            trace_data.error_info = error_info

        trace_data.init_end_time()

        client.trace(**trace_data.as_parameters)
