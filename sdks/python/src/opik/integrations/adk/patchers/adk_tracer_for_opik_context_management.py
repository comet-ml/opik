import logging
from typing import Iterator, Optional

import opentelemetry.trace
import opik.context_storage
from opik.api_objects import trace, span
from opik.decorator import span_creation_handler, arguments_helpers
from opik.api_objects import opik_client


LOGGER = logging.getLogger(__name__)

NAME_OF_LLM_SPAN_JUST_STARTED_FROM_OPIK_TRACER = "_OPIK_TRACER_STARTED_LLM_SPAN"


class ADKTracerForOpikContextManagement(opentelemetry.trace.NoOpTracer):
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
        trace_to_close_in_finally_block = None
        span_to_close_in_finally_block = None

        current_trace_data = opik.context_storage.get_trace_data()
        current_span_data = opik.context_storage.top_span_data()

        if name in self._ADK_INTERNAL_SPAN_NAME_SKIP_LIST:
            yield opentelemetry.trace.INVALID_SPAN
            return

        if (
            current_span_data is not None
            and _is_externally_created_llm_span_ready_for_immediate_finalization(
                current_span_data
            )
        ):
            # LLM span has already been updated from the OpikTracer.after_model_call.
            # ADK will create tool spans inside the llm spans (which we consider wrong practice),
            # so we manually finalize it here to avoid incorrect span nesting.
            opik.context_storage.pop_span_data(ensure_id=current_span_data.id)
            current_span_data.init_end_time()
            self.opik_client.span(**current_span_data.as_parameters)
            current_span_data = opik.context_storage.top_span_data()

        try:
            if current_trace_data is None:
                trace_to_close_in_finally_block = trace.TraceData(name=name)
                opik.context_storage.set_trace_data(trace_to_close_in_finally_block)
            elif (
                current_span_data is not None
                and _is_externally_created_llm_span_that_just_started(current_span_data)
            ):
                # LLM span has just been created and put into context storage from the OpikTracer.before_model_call
                # Not need to create a new one, just remember it to close it in finally block
                span_to_close_in_finally_block = current_span_data
                yield opentelemetry.trace.INVALID_SPAN
                return
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

            yield opentelemetry.trace.INVALID_SPAN
        finally:
            if trace_to_close_in_finally_block is not None:
                self._finalize_trace_if_its_not_finalized_yet(
                    trace_to_close_in_finally_block.id
                )
            elif span_to_close_in_finally_block is not None:
                self._finalize_span_if_its_not_finalized_yet(
                    span_to_close_in_finally_block.id
                )
            else:
                LOGGER.warning(
                    "No span or trace to finalize in ADK tracer. This is unexpected."
                )

    def _finalize_trace_if_its_not_finalized_yet(self, trace_id: str) -> None:
        trace_data = opik.context_storage.pop_trace_data(ensure_id=trace_id)
        if trace_data is not None:
            trace_data.init_end_time()
            self.opik_client.trace(**trace_data.as_parameters)

    def _finalize_span_if_its_not_finalized_yet(self, span_id: str) -> None:
        opik.context_storage.trim_span_data_stack_to_certain_span(span_id)

        span_data = opik.context_storage.pop_span_data(ensure_id=span_id)
        if span_data is not None:
            span_data.init_end_time()
            self.opik_client.span(**span_data.as_parameters)


def _is_externally_created_llm_span_ready_for_immediate_finalization(
    span_data: span.SpanData,
) -> bool:
    return (
        span_data.type == "llm"
        and span_data.name != NAME_OF_LLM_SPAN_JUST_STARTED_FROM_OPIK_TRACER
    )


def _is_externally_created_llm_span_that_just_started(
    span_data: span.SpanData,
) -> bool:
    return (
        span_data.type == "llm"
        and span_data.name == NAME_OF_LLM_SPAN_JUST_STARTED_FROM_OPIK_TRACER
    )
