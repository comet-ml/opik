import contextvars

from typing import List, Optional, Union
from .api_objects import trace, span


_spans_stack_context: contextvars.ContextVar[List[Union[span.Span]]] = (
    contextvars.ContextVar("spans_stack", default=[])
)

_current_trace_context: contextvars.ContextVar[Optional[trace.Trace]] = (
    contextvars.ContextVar("current_trace", default=None)
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
# spans_stack = spans_stack_context.get().copy()
# spans_stack_context.set(spans_stack + [new_element])
#
# And pop like that:
# spans_stack = spans_stack_context.get().copy()
# spans_stack_context.set(spans_stack[:-1])
#
# The following functions provide an API to work with ContextVars this way


def top_span() -> Optional[span.Span]:
    if span_stack_empty():
        return None

    stack = _get_stack()
    return stack[-1]


def pop_span() -> Optional[span.Span]:
    stack = _get_stack()
    _spans_stack_context.set(stack[:-1])
    return stack[-1]


def add_span(span: span.Span) -> None:
    stack = _get_stack()
    _spans_stack_context.set(stack + [span])


def span_stack_empty() -> bool:
    return len(_get_stack()) == 0


def _get_stack() -> List[span.Span]:
    return _spans_stack_context.get().copy()


def get_trace() -> Optional[trace.Trace]:
    trace = _current_trace_context.get()
    return trace


def pop_trace() -> Optional[trace.Trace]:
    trace = _current_trace_context.get()
    set_trace(None)
    return trace


def set_trace(trace: Optional[trace.Trace]) -> None:
    _current_trace_context.set(trace)


def clear_all() -> None:
    _current_trace_context.set(None)
    _spans_stack_context.set([])
