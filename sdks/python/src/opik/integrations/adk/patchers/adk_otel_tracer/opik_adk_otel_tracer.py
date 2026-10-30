import contextlib
import contextvars
import functools
import logging
from typing import (
    Any,
    Callable,
    ContextManager,
    Iterator,
    Mapping,
    Optional,
    Tuple,
    Union,
)

import opentelemetry.trace
import opentelemetry.util.types
import opik
import opik.context_storage
from opik.api_objects import trace, span
from opik.decorator import (
    span_creation_handler,
    arguments_helpers,
    error_info_collector,
)
from opik.types import DistributedTraceHeadersDict

from . import llm_span_helpers


LOGGER = logging.getLogger(__name__)

# The OTel SDK Tracer implements start_as_current_span by calling
# self.start_span, which is patched to OpikADKOtelTracer.start_span. Without this
# flag, every span delegated to a real SDK Tracer (as ADK's tracer is under
# `adk api_server`) would get Opik bookkeeping twice.
_ENTERING_INNER_START_AS_CURRENT_SPAN: contextvars.ContextVar[bool] = (
    contextvars.ContextVar(
        "opik_adk_entering_inner_start_as_current_span", default=False
    )
)

# The runner wraps its root agent in `invocation` or, on google-adk >= 2.13.0,
# `invoke_workflow {agent}`. In the runner that AgentTool starts, that span has
# `gen_ai.workflow.nested` like a nested Workflow node, so only the call site
# tells them apart.
_ENTERING_RECORD_INVOCATION: contextvars.ContextVar[bool] = contextvars.ContextVar(
    "opik_adk_entering_record_invocation", default=False
)


def skip_opik_bookkeeping_for_runner_spans(
    record_invocation: Callable[..., ContextManager[Any]],
) -> Callable[..., ContextManager[Any]]:
    @functools.wraps(record_invocation)
    @contextlib.contextmanager
    def wrapper(*args: Any, **kwargs: Any) -> Iterator[Any]:
        with contextlib.ExitStack() as exit_stack:
            token = _ENTERING_RECORD_INVOCATION.set(True)
            try:
                value = exit_stack.enter_context(record_invocation(*args, **kwargs))
            finally:
                _ENTERING_RECORD_INVOCATION.reset(token)
            yield value

    return wrapper


class OpikADKOtelTracer:
    """
    A delegating OpenTelemetry tracer wrapper for ADK integration.

    This wrapper intercepts OpenTelemetry span creation calls from ADK to drive
    Opik's trace/span bookkeeping, while still delegating to the original ADK tracer
    so that any user-configured OpenTelemetry pipeline (Cloud Trace, Jaeger, OTLP,
    etc.) continues to receive ADK spans.

    Key Features:
    - Delegates span creation to the original ADK tracer (preserves Cloud Trace /
      Jaeger / OTLP export of ADK spans)
    - Drives Opik trace/span bookkeeping alongside the real OTel spans
    - Skips Opik-side bookkeeping for internal ADK spans (still emits them to OTel)
    - Maintains context storage for nested span relationships
    - Provides special logic for handling LLM spans and their lifecycle. In particular:
        * LLM spans are created by OpikTracer.before_model_callback
        * No other Opik spans are created inside LLM spans (ADK creates tool spans inside LLM spans)
    - Supports the flows in which Opik spans are being created, updated, or finalized outside of this class.

    Attributes:
        _ADK_INTERNAL_SPAN_NAME_SKIP_LIST: List of span names that should be skipped
            during Opik tracking (still delegated to OTel).
    """

    _ADK_INTERNAL_SPAN_NAME_SKIP_LIST = [
        "invocation",  # This is the span that is created by ADK when the invocation is started.
        "call_llm",  # Wrapper span around LLM calls, added in ADK 1.29.0.
    ]

    def __init__(
        self,
        inner_start_as_current_span: Callable[
            ..., ContextManager["opentelemetry.trace.Span"]
        ],
        inner_start_span: Callable[..., "opentelemetry.trace.Span"],
        distributed_headers: Optional[DistributedTraceHeadersDict],
    ) -> None:
        """
        Initializes an instance of the class with specified callbacks and distributed trace headers.

        Args:
            inner_start_as_current_span: A callable that creates a context manager for starting
                a span as the current span. The callable is expected to return a context manager
                containing a span of type "opentelemetry.trace.Span".
            inner_start_span: A callable that starts and returns a new span of type
                "opentelemetry.trace.Span".
            distributed_headers: An optional dictionary-like object containing the distributed
                tracing headers used for propagating context across services.
        """
        self._inner_start_as_current_span = inner_start_as_current_span
        self._inner_start_span = inner_start_span
        self._distributed_headers = distributed_headers

    @property
    def opik_client(self) -> opik.Opik:
        return opik.get_global_client()

    def start_span(
        self,
        name: str,
        context: Optional[opentelemetry.trace.Context] = None,
        kind: opentelemetry.trace.SpanKind = opentelemetry.trace.SpanKind.INTERNAL,
        attributes: opentelemetry.trace.types.Attributes = None,
        links: opentelemetry.trace._Links = None,
        start_time: Optional[int] = None,
        record_exception: bool = True,
        set_status_on_exception: bool = True,
    ) -> "opentelemetry.trace.Span":
        # google-adk >= 2.12.0 opens agent (`invoke_agent ...`) and workflow
        # spans with start_span + an explicit span.end() instead of
        # start_as_current_span, so the Opik trace/span has to be opened here
        # and closed when ADK ends the returned span.
        if (
            self._skips_opik_bookkeeping(name, attributes)
            or _ENTERING_INNER_START_AS_CURRENT_SPAN.get()
        ):
            return self._inner_start_span(
                name,
                context=context,
                kind=kind,
                attributes=attributes,
                links=links,
                start_time=start_time,
                record_exception=record_exception,
                set_status_on_exception=set_status_on_exception,
            )

        # Delegate first so a failure here leaves no Opik trace/span open.
        inner_span = self._inner_start_span(
            name,
            context=context,
            kind=kind,
            attributes=attributes,
            links=links,
            start_time=start_time,
            record_exception=record_exception,
            set_status_on_exception=set_status_on_exception,
        )
        trace_to_close, span_to_close = self._start_opik_span_or_trace(name)
        return _OpikFinalizingSpan(
            inner_span=inner_span,
            on_exception=lambda exception: _record_error_info(
                trace_to_close, span_to_close, exception
            ),
            on_end=lambda exited_normally: self._finalize_opik_span_or_trace(
                trace_to_close, span_to_close, exited_normally
            ),
        )

    @opentelemetry.util._decorator._agnosticcontextmanager
    def start_as_current_span(
        self,
        name: str,
        context: Optional[opentelemetry.trace.Context] = None,
        kind: opentelemetry.trace.SpanKind = opentelemetry.trace.SpanKind.INTERNAL,
        attributes: opentelemetry.trace.types.Attributes = None,
        links: opentelemetry.trace._Links = None,
        start_time: Optional[int] = None,
        record_exception: bool = True,
        set_status_on_exception: bool = True,
        end_on_exit: bool = True,
    ) -> Iterator["opentelemetry.trace.Span"]:
        if self._skips_opik_bookkeeping(name, attributes):
            # Skip Opik bookkeeping for ADK-internal spans but still delegate
            # to the original tracer so OTel exporters see them.
            with self._inner_start_as_current_span(
                name,
                context=context,
                kind=kind,
                attributes=attributes,
                links=links,
                start_time=start_time,
                record_exception=record_exception,
                set_status_on_exception=set_status_on_exception,
                end_on_exit=end_on_exit,
            ) as inner_span:
                yield inner_span
            return

        trace_to_close_in_finally_block, span_to_close_in_finally_block = (
            self._start_opik_span_or_trace(name)
        )
        exited_normally = False

        try:
            with contextlib.ExitStack() as exit_stack:
                inner_span = _enter_without_start_span_bookkeeping(
                    exit_stack,
                    self._inner_start_as_current_span(
                        name,
                        context=context,
                        kind=kind,
                        attributes=attributes,
                        links=links,
                        start_time=start_time,
                        record_exception=record_exception,
                        set_status_on_exception=set_status_on_exception,
                        end_on_exit=end_on_exit,
                    ),
                )
                yield inner_span
            exited_normally = True
        except Exception as exception:
            # The expected exception here is the exception that happened during the agent
            # execution and was re-raised from the `opentelemetry.util._decorator._agnosticcontextmanagergenerator`s
            # `__exit__` method via the `gen.throw(...)` statement.
            #
            # More context: https://docs.python.org/3.12/reference/expressions.html#examples
            _record_error_info(
                trace_to_close_in_finally_block,
                span_to_close_in_finally_block,
                exception,
            )
            raise
        finally:
            self._finalize_opik_span_or_trace(
                trace_to_close_in_finally_block,
                span_to_close_in_finally_block,
                exited_normally,
            )

    def _skips_opik_bookkeeping(
        self, name: str, attributes: opentelemetry.trace.types.Attributes
    ) -> bool:
        if (
            name in self._ADK_INTERNAL_SPAN_NAME_SKIP_LIST
            or _ENTERING_RECORD_INVOCATION.get()
        ):
            return True

        # google-adk >= 2.13.0 replaces `invocation` with the runner's own
        # `invoke_workflow {root}` span. Tracking it would make it the trace and
        # push the root agent one level down, so it is skipped like `invocation`.
        # Only nested workflows carry `gen_ai.workflow.nested`.
        return (
            attributes is not None
            and attributes.get("gen_ai.operation.name") == "invoke_workflow"
            and not attributes.get("gen_ai.workflow.nested")
        )

    def _start_opik_span_or_trace(
        self, name: str
    ) -> Tuple[Optional[trace.TraceData], Optional[span.SpanData]]:
        self._finalize_llm_spans_skipped_by_after_model_callback()

        current_trace_data = opik.context_storage.get_trace_data()
        current_span_data = opik.context_storage.top_span_data()

        if (
            current_span_data is not None
            and llm_span_helpers.is_externally_created_llm_span_ready_for_immediate_finalization(
                current_span_data
            )
        ):
            # LLM span has already been updated from the OpikTracer.after_model_call.
            # ADK will create tool spans inside the llm spans (which we consider wrong practice),
            # so we manually finalize it here to avoid incorrect span nesting.
            opik.context_storage.pop_span_data(ensure_id=current_span_data.id)
            # A span with an end time was already sent by after_model_callback,
            # which couldn't pop it (detached context); sending it again would
            # only duplicate it.
            if current_span_data.end_time is None:
                current_span_data.init_end_time()
                if opik.is_tracing_active():
                    self.opik_client.__internal_api__span__(
                        **current_span_data.as_parameters
                    )
            current_span_data = opik.context_storage.top_span_data()

        return _prepare_trace_and_span_to_be_finalized(
            name=name,
            current_trace_data=current_trace_data,
            current_span_data=current_span_data,
            distributed_headers=self._distributed_headers,
        )

    def _finalize_opik_span_or_trace(
        self,
        trace_to_close: Optional[trace.TraceData],
        span_to_close: Optional[span.SpanData],
        exited_normally: bool,
    ) -> None:
        self._finalize_llm_spans_skipped_by_after_model_callback()

        if trace_to_close is not None:
            self._ensure_trace_is_finalized(trace_to_close.id)

        if span_to_close is not None:
            # ADK ends its inference span (`generate_content {model}`) as soon
            # as it records the final response, before running
            # after_model_callback (google.adk.telemetry._instrumentation.
            # record_llm_response, seen in google-adk 2.9.2). Finalizing the LLM
            # span on that normal exit would send it without output and usage,
            # and that copy races after_model_callback's complete one
            # downstream. So on a normal exit the span is left on the stack
            # for after_model_callback. If that callback never runs, the next
            # span boundary finalizes the span. On any non-normal exit
            # (exception or cancellation) there may be no after_model_callback,
            # so the span is finalized here as before.
            if exited_normally and (
                llm_span_helpers.is_externally_created_llm_span_that_just_started(
                    span_to_close
                )
            ):
                span_to_close.update(
                    metadata={
                        llm_span_helpers.SPAN_STATUS: llm_span_helpers.LLMSpanStatus.AWAITING_AFTER_MODEL_CALLBACK
                    }
                )
            else:
                self._ensure_span_is_finalized(span_to_close.id)

        if trace_to_close is None and span_to_close is None:
            LOGGER.warning(
                "No span or trace to finalize in ADK tracer. This is unexpected."
            )

    def _finalize_llm_spans_skipped_by_after_model_callback(self) -> None:
        # ADK skips the agent's after_model_callbacks when a plugin (or an earlier
        # callback) returns a response, so a deferred LLM span can still be waiting
        # at the next span boundary. Finalize it as-is rather than lose it.
        while (
            (span_data := opik.context_storage.top_span_data()) is not None
            and llm_span_helpers.is_externally_created_llm_span_awaiting_after_model_callback(
                span_data
            )
        ):
            opik.context_storage.pop_span_data(ensure_id=span_data.id)
            span_data.init_end_time()
            if opik.is_tracing_active():
                self.opik_client.__internal_api__span__(**span_data.as_parameters)

    def _ensure_trace_is_finalized(self, trace_id: str) -> None:
        trace_data = opik.context_storage.pop_trace_data(ensure_id=trace_id)
        if trace_data is not None:
            trace_data.init_end_time()
            if opik.is_tracing_active():
                self.opik_client.__internal_api__trace__(**trace_data.as_parameters)

    def _ensure_span_is_finalized(self, span_id: str) -> None:
        opik.context_storage.trim_span_data_stack_to_certain_span(span_id)

        span_data = opik.context_storage.pop_span_data(ensure_id=span_id)
        if span_data is not None:
            span_data.init_end_time()
            if opik.is_tracing_active():
                self.opik_client.__internal_api__span__(**span_data.as_parameters)


def _enter_without_start_span_bookkeeping(
    exit_stack: contextlib.ExitStack,
    span_context_manager: ContextManager[opentelemetry.trace.Span],
) -> opentelemetry.trace.Span:
    token = _ENTERING_INNER_START_AS_CURRENT_SPAN.set(True)
    try:
        return exit_stack.enter_context(span_context_manager)
    finally:
        _ENTERING_INNER_START_AS_CURRENT_SPAN.reset(token)


def _record_error_info(
    trace_data: Optional[trace.TraceData],
    span_data: Optional[span.SpanData],
    exception: BaseException,
) -> None:
    error_info = error_info_collector.collect(exception)
    if trace_data is not None:
        trace_data.update(error_info=error_info)
    if span_data is not None:
        span_data.update(error_info=error_info)


class _OpikFinalizingSpan(opentelemetry.trace.Span):
    """
    Wraps a span returned by the inner tracer's start_span so that the Opik
    trace/span opened alongside it is finalized when ADK ends the span. OTel
    operations are delegated, so exporters and context propagation see the real
    span; end, record_exception and is_recording also drive the Opik bookkeeping.
    """

    def __init__(
        self,
        inner_span: opentelemetry.trace.Span,
        on_exception: Callable[[BaseException], None],
        on_end: Callable[[bool], None],
    ) -> None:
        self._inner_span = inner_span
        self._on_exception = on_exception
        self._on_end = on_end
        self._exception_recorded = False
        self._ended = False

    def end(self, end_time: Optional[int] = None) -> None:
        try:
            self._inner_span.end(end_time=end_time)
        finally:
            if not self._ended:
                self._ended = True
                self._on_end(not self._exception_recorded)

    def record_exception(
        self,
        exception: BaseException,
        attributes: opentelemetry.util.types.Attributes = None,
        timestamp: Optional[int] = None,
        escaped: bool = False,
    ) -> None:
        self._inner_span.record_exception(
            exception, attributes=attributes, timestamp=timestamp, escaped=escaped
        )
        if not self._exception_recorded:
            self._exception_recorded = True
            self._on_exception(exception)

    def get_span_context(self) -> opentelemetry.trace.SpanContext:
        return self._inner_span.get_span_context()

    def set_attributes(
        self, attributes: Mapping[str, opentelemetry.util.types.AttributeValue]
    ) -> None:
        self._inner_span.set_attributes(attributes)

    def set_attribute(
        self, key: str, value: opentelemetry.util.types.AttributeValue
    ) -> None:
        self._inner_span.set_attribute(key, value)

    def add_event(
        self,
        name: str,
        attributes: opentelemetry.util.types.Attributes = None,
        timestamp: Optional[int] = None,
    ) -> None:
        self._inner_span.add_event(name, attributes=attributes, timestamp=timestamp)

    def add_link(
        self,
        context: opentelemetry.trace.SpanContext,
        attributes: opentelemetry.util.types.Attributes = None,
    ) -> None:
        self._inner_span.add_link(context, attributes=attributes)

    def update_name(self, name: str) -> None:
        self._inner_span.update_name(name)

    def is_recording(self) -> bool:
        # ADK records an escaping exception only on a recording span. Opik is
        # recording this span even when the inner one is a NonRecordingSpan
        # (no TracerProvider installed), whose writes are no-ops anyway.
        return not self._ended

    def set_status(
        self,
        status: Union[opentelemetry.trace.Status, opentelemetry.trace.StatusCode],
        description: Optional[str] = None,
    ) -> None:
        self._inner_span.set_status(status, description)

    def __getattr__(self, name: str) -> Any:
        # Covers SDK-span attributes ADK reads directly (e.g. start_time and
        # end_time for its duration metrics).
        return getattr(self._inner_span, name)


def _prepare_trace_and_span_to_be_finalized(
    name: str,
    current_trace_data: Optional[trace.TraceData],
    current_span_data: Optional[span.SpanData],
    distributed_headers: Optional[DistributedTraceHeadersDict],
) -> Tuple[Optional[trace.TraceData], Optional[span.SpanData]]:
    """
    Prepares a trace and a span to be finalized in the finally block.
    """

    trace_to_close_in_finally_block = None
    span_to_close_in_finally_block = None

    if distributed_headers is not None and current_span_data is None:
        # create top root span connected to distributed headers
        start_span_arguments = arguments_helpers.StartSpanParameters(
            name=name,
            type="general",
        )
        span_to_close_in_finally_block = (
            span_creation_handler.create_span_respecting_context(
                start_span_arguments=start_span_arguments,
                distributed_trace_headers=distributed_headers,
            ).span_data
        )
        opik.context_storage.add_span_data(span_to_close_in_finally_block)

    elif current_trace_data is None and distributed_headers is None:
        # create trace only if no distributed_headers provided
        trace_to_close_in_finally_block = trace.TraceData(name=name)
        opik.context_storage.set_trace_data(trace_to_close_in_finally_block)

    elif (
        current_span_data is not None
        and llm_span_helpers.is_externally_created_llm_span_that_just_started(
            current_span_data
        )
    ):
        # LLM span has just been created and put into context storage from the OpikTracer.before_model_call
        # Not need to create a new one, just remember it to close it in finally block
        span_to_close_in_finally_block = current_span_data

    else:
        # create child span
        start_span_arguments = arguments_helpers.StartSpanParameters(
            name=name,
            type="general",
        )
        span_to_close_in_finally_block = (
            span_creation_handler.create_span_respecting_context(
                start_span_arguments=start_span_arguments,
                distributed_trace_headers=None,
            ).span_data
        )
        opik.context_storage.add_span_data(span_to_close_in_finally_block)

    return trace_to_close_in_finally_block, span_to_close_in_finally_block
