from typing import Optional, Dict, Any, List
from opik.types import UsageDict, DistributedTraceHeadersDict
from opik.api_objects import span, trace

from . import context_storage, exceptions


def get_current_span_data() -> Optional[span.SpanData]:
    """
    Returns current span created by track() decorator or None if no span was found.
    Context-wise.
    """
    span_data = context_storage.top_span_data()
    if span_data is None:
        return None

    return span.SpanData(**span_data.__dict__)


def get_current_trace_data() -> Optional[trace.TraceData]:
    """
    Returns current trace created by track() decorator or None if no trace was found.
    Context-wise.
    """
    trace_data = context_storage.get_trace_data()
    if trace_data is None:
        return None

    return trace.TraceData(**trace_data.__dict__)


def get_distributed_trace_headers() -> DistributedTraceHeadersDict:
    current_span_data = context_storage.top_span_data()

    if current_span_data is None:
        raise Exception("There is no span in the context.")

    return DistributedTraceHeadersDict(
        opik_trace_id=current_span_data.trace_id,
        opik_parent_span_id=current_span_data.id,
    )


def update_current_span(
    input: Optional[Dict[str, Any]] = None,
    output: Optional[Dict[str, Any]] = None,
    metadata: Optional[Dict[str, Any]] = None,
    tags: Optional[List[str]] = None,
    usage: Optional[UsageDict] = None,
) -> None:
    new_params = {
        "input": input,
        "output": output,
        "metadata": metadata,
        "tags": tags,
        "usage": usage,
    }
    current_span_data = context_storage.top_span_data()
    if current_span_data is None:
        raise exceptions.OpikException("There is no span in the context.")

    current_span_data.update(new_params)


def update_current_trace(
    input: Optional[Dict[str, Any]] = None,
    output: Optional[Dict[str, Any]] = None,
    metadata: Optional[Dict[str, Any]] = None,
    tags: Optional[List[str]] = None,
) -> None:
    new_params = {
        "input": input,
        "output": output,
        "metadata": metadata,
        "tags": tags,
    }
    current_trace_data = context_storage.get_trace_data()
    if current_trace_data is None:
        raise exceptions.OpikException("There is no trace in the context.")

    current_trace_data.update(new_params)


__all__ = [
    "get_current_span_data",
    "get_current_trace_data",
    "update_current_span",
    "update_current_trace",
    "get_distributed_trace_headers",
]
