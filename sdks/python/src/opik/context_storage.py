import contextvars

from typing import List, Optional
from opik.api_objects import span, trace

_current_trace_data_context: contextvars.ContextVar[Optional[trace.TraceData]] = (
    contextvars.ContextVar("current_trace_data", default=None)
)

_spans_data_stack_context: contextvars.ContextVar[List[span.SpanData]] = (
    contextvars.ContextVar("spans_data_stack", default=[])
)

# Read if you are going to change this module.
#
# If you want to add or pop the span from the stack
# you must NOT do it like that: spans_stack_context.get().append()
#
# If you modify the default object in ContextVar (which is a MUTABLE list)
# these changes will be reflected in any new context which breaks the whole idea
# of ContextVar.
#
# You should append to it like that:
# span_data_stack = span_data_stack_context.get().copy()
# span_data_stack_context.set(span_data_stack + [new_element])
#
# And pop like that:
# span_data_stack = spans_stack_context.get().copy()
# span_data_stack_context.set(span_data_stack[:-1])
#
# The following functions provide an API to work with ContextVars this way


def top_span_data() -> Optional[span.SpanData]:
    if span_data_stack_empty():
        return None

    stack = _get_data_stack()
    return stack[-1]


def pop_span_data() -> Optional[span.SpanData]:
    stack = _get_data_stack()
    _spans_data_stack_context.set(stack[:-1])
    return stack[-1]


def add_span_data(span: span.SpanData) -> None:
    stack = _get_data_stack()
    _spans_data_stack_context.set(stack + [span])


def span_data_stack_empty() -> bool:
    return len(_get_data_stack()) == 0


def _get_data_stack() -> List[span.SpanData]:
    return _spans_data_stack_context.get().copy()


def get_trace_data() -> Optional[trace.TraceData]:
    trace = _current_trace_data_context.get()
    return trace


def pop_trace_data() -> Optional[trace.TraceData]:
    trace = _current_trace_data_context.get()
    set_trace_data(None)
    return trace


def set_trace_data(trace: Optional[trace.TraceData]) -> None:
    _current_trace_data_context.set(trace)


def clear_all() -> None:
    _current_trace_data_context.set(None)
    _spans_data_stack_context.set([])
