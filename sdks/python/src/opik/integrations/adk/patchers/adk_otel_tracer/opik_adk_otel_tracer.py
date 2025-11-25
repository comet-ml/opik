import logging
from typing import Iterator, Optional, Tuple

import opentelemetry.trace
import opik
import opik.context_storage
from opik.api_objects import trace, span
from opik.decorator import (
    span_creation_handler,
    arguments_helpers,
    error_info_collector,
)
from opik.api_objects import opik_client

from . import llm_span_helpers


LOGGER = logging.getLogger(__name__)


class OpikADKOtelTracer(opentelemetry.trace.NoOpTracer):
    """
    A patched OpenTelemetry tracer for ADK integration.

    This tracer intercepts OpenTelemetry span creation calls from ADK and converts them
    into Opik spans and traces, providing seamless integration between ADK's telemetry
    system and Opik's tracking capabilities.

    Key Features:
    - Converts OpenTelemetry spans to Opik spans and traces
    - Handles trace lifecycle management automatically
    - Skips internal ADK spans that shouldn't have Opik spans created for them
    - Maintains context storage for nested span relationships
    - Provides special logic for handling LLM spans and their lifecycle. In particular:
        * LLM spans are created by OpikTracer.before_model_callback
        * No other Opik spans are created inside LLM spans (ADK creates tool spans inside LLM spans)
    - Supports the flows in which Opik spans are being created, updated or finalized outside of this class.

    The tracer operates as a no-op from OpenTelemetry's perspective (returning INVALID_SPAN)
    while creating corresponding Opik tracking objects in the background.

    Args:
        opik_client: The Opik client instance used for creating spans and traces

    Attributes:
        _ADK_INTERNAL_SPAN_NAME_SKIP_LIST: List of span names that should be skipped
            during tracking.
    """

    _ADK_INTERNAL_SPAN_NAME_SKIP_LIST = [
        "invocation",  # This is the span that is created by ADK when the invocation is started.
    ]

    def __init__(self, opik_client: opik_client.Opik):
        self.opik_client = opik_client

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
        return opentelemetry.trace.INVALID_SPAN

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
            yield opentelemetry.trace.INVALID_SPAN
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
                self.opik_client.span(**current_span_data.as_parameters)
            current_span_data = opik.context_storage.top_span_data()

        try:
            trace_to_close_in_finally_block, span_to_close_in_finally_block = (
                _prepare_trace_and_span_to_be_finalized(
                    name=name,
                    current_trace_data=current_trace_data,
                    current_span_data=current_span_data,
                )
            )

            yield opentelemetry.trace.INVALID_SPAN
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
                self.opik_client.trace(**trace_data.as_parameters)

    def _ensure_span_is_finalized(self, span_id: str) -> None:
        opik.context_storage.trim_span_data_stack_to_certain_span(span_id)

        span_data = opik.context_storage.pop_span_data(ensure_id=span_id)
        if span_data is not None:
            span_data.init_end_time()
            if opik.is_tracing_active():
                self.opik_client.span(**span_data.as_parameters)


def _prepare_trace_and_span_to_be_finalized(
    name: str,
    current_trace_data: Optional[trace.TraceData],
    current_span_data: Optional[span.SpanData],
) -> Tuple[Optional[trace.TraceData], Optional[span.SpanData]]:
    """
    Prepares a trace and a span to be finalized in the finally block.
    """

    trace_to_close_in_finally_block = None
    span_to_close_in_finally_block = None

    if current_trace_data is None:
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
        start_span_arguments = arguments_helpers.StartSpanParameters(
            name=name,
            type="general",
        )

        _, span_to_close_in_finally_block = (
            span_creation_handler.create_span_respecting_context(
                start_span_arguments=start_span_arguments,
                distributed_trace_headers=None,
            )
        )
        opik.context_storage.add_span_data(span_to_close_in_finally_block)

    return trace_to_close_in_finally_block, span_to_close_in_finally_block
