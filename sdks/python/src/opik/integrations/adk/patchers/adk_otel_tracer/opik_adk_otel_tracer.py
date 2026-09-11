import logging
from typing import Callable, ContextManager, Iterator, Optional, Tuple

import opentelemetry.trace
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
        # Delegate to the original ADK tracer so user-configured OTel exporters
        # (Cloud Trace, Jaeger, etc.) still receive the span. ADK does not call
        # this method for any span Opik needs to track today, so no Opik-side
        # bookkeeping is required here.
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
        if name in self._ADK_INTERNAL_SPAN_NAME_SKIP_LIST:
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

        trace_to_close_in_finally_block = None
        span_to_close_in_finally_block = None

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
            current_span_data.init_end_time()
            if opik.is_tracing_active():
                self.opik_client.__internal_api__span__(
                    **current_span_data.as_parameters
                )
            current_span_data = opik.context_storage.top_span_data()

        try:
            trace_to_close_in_finally_block, span_to_close_in_finally_block = (
                _prepare_trace_and_span_to_be_finalized(
                    name=name,
                    current_trace_data=current_trace_data,
                    current_span_data=current_span_data,
                    distributed_headers=self._distributed_headers,
                )
            )

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
        except Exception as exception:
            # The expected exception here is the exception that happened during the agent
            # execution and was re-raised from the `opentelemetry.util._decorator._agnosticcontextmanagergenerator`s
            # `__exit__` method via the `gen.throw(...)` statement.
            #
            # More context: https://docs.python.org/3.12/reference/expressions.html#examples
            error_info = error_info_collector.collect(exception)

            if trace_to_close_in_finally_block is not None:
                trace_to_close_in_finally_block.update(error_info=error_info)
            if span_to_close_in_finally_block is not None:
                span_to_close_in_finally_block.update(error_info=error_info)
            raise
        finally:
            if trace_to_close_in_finally_block is not None:
                self._ensure_trace_is_finalized(trace_to_close_in_finally_block.id)

            if span_to_close_in_finally_block is not None:
                self._ensure_span_is_finalized(span_to_close_in_finally_block.id)

            if (
                trace_to_close_in_finally_block is None
                and span_to_close_in_finally_block is None
            ):
                LOGGER.warning(
                    "No span or trace to finalize in ADK tracer. This is unexpected."
                )

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
