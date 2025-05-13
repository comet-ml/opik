import contextvars
import contextlib

from typing import List, Optional, Generator
from opik.api_objects import span, trace


class OpikContextStorage:
    """
    Class responsible for keeping and providing access to the context of
    spans and traces.

    Read if you are going to change this class.

    If you want to add or pop the span from the stack
    you must NOT do it like that: spans_stack_context.get().append()

    If you modify the default object in ContextVar (which is a MUTABLE list)
    these changes will be reflected in any new context which breaks the whole idea
    of ContextVar.

    You should append to it like that:
    span_data_stack = span_data_stack_context.get().copy()
    span_data_stack_context.set(span_data_stack + [new_element])

    And pop like that:
    span_data_stack = spans_stack_context.get().copy()
    spans_stack_context.set(span_data_stack[:-1])

    The following functions provide an API to work with ContextVars this way
    """

    def __init__(self) -> None:
        self._current_trace_data_context: contextvars.ContextVar[
            Optional[trace.TraceData]
        ] = contextvars.ContextVar("current_trace_data", default=None)
        self._spans_data_stack_context: contextvars.ContextVar[List[span.SpanData]] = (
            contextvars.ContextVar("spans_data_stack", default=[])
        )

    def top_span_data(self) -> Optional[span.SpanData]:
        if self.span_data_stack_empty():
            return None
        stack = self._get_data_stack()
        return stack[-1]

    def pop_span_data(self) -> Optional[span.SpanData]:
        stack = self._get_data_stack()
        self._spans_data_stack_context.set(stack[:-1])
        return stack[-1]

    def add_span_data(self, span: span.SpanData) -> None:
        stack = self._get_data_stack()
        self._spans_data_stack_context.set(stack + [span])

    def span_data_stack_empty(self) -> bool:
        return len(self._get_data_stack()) == 0

    def _get_data_stack(self) -> List[span.SpanData]:
        return self._spans_data_stack_context.get().copy()

    def get_trace_data(self) -> Optional[trace.TraceData]:
        trace = self._current_trace_data_context.get()
        return trace

    def pop_trace_data(self) -> Optional[trace.TraceData]:
        trace = self._current_trace_data_context.get()
        self.set_trace_data(None)
        return trace

    def set_trace_data(self, trace: Optional[trace.TraceData]) -> None:
        self._current_trace_data_context.set(trace)

    def clear_all(self) -> None:
        self._current_trace_data_context.set(None)
        self._spans_data_stack_context.set([])


_context_storage = OpikContextStorage()

top_span_data = _context_storage.top_span_data
pop_span_data = _context_storage.pop_span_data
add_span_data = _context_storage.add_span_data
span_data_stack_empty = _context_storage.span_data_stack_empty
get_trace_data = _context_storage.get_trace_data
pop_trace_data = _context_storage.pop_trace_data
set_trace_data = _context_storage.set_trace_data
clear_all = _context_storage.clear_all


def get_current_context_instance() -> OpikContextStorage:
    return _context_storage


@contextlib.contextmanager
def temporary_context(
    span_data: span.SpanData, trace_data: Optional[trace.TraceData]
) -> Generator[None, None, None]:
    """
    Temporary adds span and trace data to the context.
    If trace_data is None, it has no effect on the current trace in the context.
    """
    try:
        original_trace = get_trace_data()

        if trace_data is not None:
            set_trace_data(trace=trace_data)

        add_span_data(span_data)

        yield
    finally:
        set_trace_data(original_trace)
        pop_span_data()
