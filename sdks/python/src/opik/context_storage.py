import contextvars
import contextlib
import logging

from typing import List, Optional, Generator, Tuple
from opik.api_objects import span, trace

LOGGER = logging.getLogger(__name__)


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
        self._current_project_name_context: contextvars.ContextVar[Optional[str]] = (
            contextvars.ContextVar("current_project_name", default=None)
        )
        self._current_project_name_owner_context: contextvars.ContextVar[
            Optional[str]
        ] = contextvars.ContextVar("current_project_name_owner", default=None)

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

    def get_context_project_name(self) -> Optional[str]:
        return self._current_project_name_context.get()

    def try_acquire_context_project_name(
        self, project_name: str, owner_id: str
    ) -> bool:
        """Try to set the project name for the current context.

        The first caller becomes the owner. Subsequent calls with a
        different ``owner_id`` are ignored (with a warning if the
        requested project name differs).

        Returns ``True`` if this call became the owner, ``False`` otherwise.
        """
        current_owner = self._current_project_name_owner_context.get()
        if current_owner is not None:
            current_project = self._current_project_name_context.get()
            if current_project != project_name:
                LOGGER.warning(
                    "Attempted to set project name to %r, but it is already "
                    "set to %r by the enclosing trace/span. The outer "
                    "project name will be used.",
                    project_name,
                    current_project,
                )
            return False

        self._current_project_name_context.set(project_name)
        self._current_project_name_owner_context.set(owner_id)
        return True

    def release_context_project_name_if_owner(self, owner_id: str) -> None:
        """Release project name ownership if ``owner_id`` matches."""
        if self._current_project_name_owner_context.get() == owner_id:
            self._current_project_name_context.set(None)
            self._current_project_name_owner_context.set(None)

    def _raw_set_context_project_name(
        self, project_name: Optional[str]
    ) -> contextvars.Token:
        """Low-level set used by ``temporary_context`` for save/restore."""
        return self._current_project_name_context.set(project_name)

    def _raw_reset_context_project_name(self, token: contextvars.Token) -> None:
        """Low-level reset used by ``temporary_context`` for save/restore."""
        self._current_project_name_context.reset(token)

    def clear_spans(self) -> None:
        self._spans_data_stack_context.set(tuple())

    def clear_all(self) -> None:
        self._current_trace_data_context.set(None)
        self._current_project_name_context.set(None)
        self._current_project_name_owner_context.set(None)
        self.clear_spans()


_context_storage = OpikContextStorage()

top_span_data = _context_storage.top_span_data
pop_span_data = _context_storage.pop_span_data
add_span_data = _context_storage.add_span_data
span_data_stack_empty = _context_storage.span_data_stack_empty
get_trace_data = _context_storage.get_trace_data
pop_trace_data = _context_storage.pop_trace_data
set_trace_data = _context_storage.set_trace_data
get_context_project_name = _context_storage.get_context_project_name
try_acquire_context_project_name = _context_storage.try_acquire_context_project_name
release_context_project_name_if_owner = (
    _context_storage.release_context_project_name_if_owner
)
clear_all = _context_storage.clear_all
span_data_stack_size = _context_storage.span_data_stack_size
trim_span_data_stack_to_certain_span = (
    _context_storage.trim_span_data_stack_to_certain_span
)


def resolve_project_name(
    default: Optional[str],
    caller: str,
) -> Optional[str]:
    """Resolve project name for an integration or callback.

    If an active project context exists (set by ``@track`` or
    ``opik.project_context``), it takes precedence over *default*.
    A warning is logged when *default* is overridden.
    """
    context_project = get_context_project_name()
    if context_project is not None:
        if default is not None and default != context_project:
            LOGGER.warning(
                '%s was initialized with project "%s", but the active '
                'context uses "%s". The context project will be used.',
                caller,
                default,
                context_project,
            )
        return context_project
    return default


def get_current_context_instance() -> OpikContextStorage:
    return _context_storage


@contextlib.contextmanager
def project_context(project_name: str) -> Generator[None, None, None]:
    """
    Context manager that sets the project name for all Opik operations
    (traces, spans, agent configs, etc.) within the block.

    The first context to set a project name becomes the owner. Nested
    ``project_context`` or ``@track(project_name=...)`` calls with a
    different name are ignored (a warning is logged) and the outer
    project name is preserved.

    Usage::

        with project_context("customer-support"):
            customer_support_agent(query)
    """
    owner_id = f"project_context_{id(project_name)}_{id(object())}"
    acquired = try_acquire_context_project_name(project_name, owner_id)
    try:
        yield
    finally:
        if acquired:
            release_context_project_name_if_owner(owner_id)


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

        project_token = None
        if span_data.project_name is not None:
            project_token = _context_storage._raw_set_context_project_name(
                span_data.project_name
            )

        add_span_data(span_data)

        yield
    finally:
        set_trace_data(original_trace)
        if project_token is not None:
            _context_storage._raw_reset_context_project_name(project_token)
        pop_span_data()
