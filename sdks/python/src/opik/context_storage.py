import contextvars
import contextlib

from typing import List, Optional, Generator, Tuple
from opik.api_objects import span, trace


class OpikContextStorage:
    """
    Manages span and trace context using Python's contextvars.

    ## IMPORTANT: Working with ContextVars

    This class uses ContextVars to maintain isolated stacks across different
    execution contexts (like threads or async tasks). To ensure proper isolation and safety,
    this implementation uses immutable tuples for stack storage.

    ### DO use immutable data structures and create-new-set pattern:

    For adding elements:
    ```python
    # Get current tuple and create a new one with added element
    stack = spans_stack_context.get()
    spans_stack_context.set(stack + (new_element,))
    ```

    For removing elements:
    ```python
    # Get current tuple and create a new one without the last element
    stack = spans_stack_context.get()
    spans_stack_context.set(stack[:-1])
    ```

    The methods in this class follow these patterns and provide a safe API
    for manipulating the context stacks.
    """

    def __init__(self) -> None:
        self._current_trace_data_context: contextvars.ContextVar[
            Optional[trace.TraceData]
        ] = contextvars.ContextVar("current_trace_data", default=None)
        default_span_stack: Tuple[span.SpanData, ...] = tuple()
        self._spans_data_stack_context: contextvars.ContextVar[
            Tuple[span.SpanData, ...]
        ] = contextvars.ContextVar("spans_data_stack", default=default_span_stack)

    def _has_span_id(self, span_id: str) -> bool:
        return any(span.id == span_id for span in self._spans_data_stack_context.get())

    def trim_span_data_stack_to_certain_span(self, span_id: str) -> None:
        """
        If span with the given id exists in the stack, eliminates the spans from the stack
        until the span with the given id is at the top.

        Intended to be used in the modules that perform unsafe manipulations with the
        span data stack (when there is a risk of missing the pop operation, e.g. in callback-based integrations).

        When the id of the span that SHOULD be on top is known, we can trim
        the stack to remove hanged spans if there are any.

        Args:
            span_id: The id of the span to trim the stack to.
        Returns:
            None
        """
        if not self._has_span_id(span_id):
            return

        stack = self._spans_data_stack_context.get()
        new_stack_list: List[span.SpanData] = []
        for span_data in stack:
            new_stack_list.append(span_data)
            if span_data.id == span_id:
                break

        self._spans_data_stack_context.set(tuple(new_stack_list))

    def top_span_data(self) -> Optional[span.SpanData]:
        if self.span_data_stack_empty():
            return None
        stack = self._spans_data_stack_context.get()
        return stack[-1]

    def pop_span_data(
        self,
        ensure_id: Optional[str] = None,
    ) -> Optional[span.SpanData]:
        """
        Pops the span from the stack.
        Args:
            ensure_id: If provided, it will pop the span only if it has the given id.
                Intended to be used in the modules that perform unsafe manipulations with the
                span data stack (when there is a risk of missing the add or pop operation,
                e.g. in callback-based integrations), to make sure the correct span is popped.
        Returns:
            The span that was popped from the stack or None.
        """
        if self.span_data_stack_empty():
            return None

        if ensure_id is None:
            stack = self._spans_data_stack_context.get()
            self._spans_data_stack_context.set(stack[:-1])
            return stack[-1]

        if self.top_span_data().id == ensure_id:  # type: ignore
            return self.pop_span_data()

        STACK_IS_EMPTY_OR_THE_ID_DOES_NOT_MATCH = None
        return STACK_IS_EMPTY_OR_THE_ID_DOES_NOT_MATCH

    def add_span_data(self, span: span.SpanData) -> None:
        stack = self._spans_data_stack_context.get()
        self._spans_data_stack_context.set(stack + (span,))

    def span_data_stack_empty(self) -> bool:
        return len(self._spans_data_stack_context.get()) == 0

    def span_data_stack_size(self) -> int:
        return len(self._spans_data_stack_context.get())

    def get_trace_data(self) -> Optional[trace.TraceData]:
        trace_data = self._current_trace_data_context.get()
        return trace_data

    def pop_trace_data(
        self, ensure_id: Optional[str] = None
    ) -> Optional[trace.TraceData]:
        """
        Pops the trace from the context.
        Args:
            ensure_id: If provided, it will pop the trace only if it has the given id.
                Intended to be used in the modules that perform unsafe manipulations with the
                trace data (when there is a risk of missing the set operation,
                e.g. in callback-based integrations), to make sure the correct trace is popped.
        Returns:
            The trace that was popped from the context or None.
        """
        trace_data = self._current_trace_data_context.get()

        if trace_data is None:
            return None

        if ensure_id is not None and trace_data.id != ensure_id:
            return None

        self.set_trace_data(None)
        return trace_data

    def set_trace_data(self, trace: Optional[trace.TraceData]) -> None:
        self._current_trace_data_context.set(trace)

    def clear_spans(self) -> None:
        self._spans_data_stack_context.set(tuple())

    def clear_all(self) -> None:
        self._current_trace_data_context.set(None)
        self.clear_spans()


_context_storage = OpikContextStorage()

top_span_data = _context_storage.top_span_data
pop_span_data = _context_storage.pop_span_data
add_span_data = _context_storage.add_span_data
span_data_stack_empty = _context_storage.span_data_stack_empty
get_trace_data = _context_storage.get_trace_data
pop_trace_data = _context_storage.pop_trace_data
set_trace_data = _context_storage.set_trace_data
clear_all = _context_storage.clear_all
span_data_stack_size = _context_storage.span_data_stack_size
trim_span_data_stack_to_certain_span = (
    _context_storage.trim_span_data_stack_to_certain_span
)


def get_current_context_instance() -> OpikContextStorage:
    return _context_storage


@contextlib.contextmanager
def temporary_context(
    span_data: span.SpanData, trace_data: Optional[trace.TraceData]
) -> Generator[None, None, None]:
    """
    Temporarily adds span and trace data to the context.
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
