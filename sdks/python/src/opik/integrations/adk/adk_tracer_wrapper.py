from contextlib import contextmanager
from typing import Iterator, Optional, Set

import opentelemetry.trace
import opik.context_storage
from opik.api_objects import span, trace
from opik.decorator import span_creation_handler, arguments_helpers
from opik.api_objects import opik_client


adk_operations_skip_list = [
    "invocation",
    "call_llm", # opik tracer will handle these spans
]

class ADKOpenTelemetryTracerPatched(opentelemetry.trace.NoOpTracer):
    """The default Tracer, used when no Tracer implementation is available.

    All operations are no-op.
    """
    def __init__(self, opik_client: opik_client.Opik):
        self._opik_client = opik_client

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
        if name.startswith("execute_tool"):
            pass

        current_trace_data = opik.context_storage.get_trace_data()
        current_span_data = opik.context_storage.top_span_data()


        if name in adk_operations_skip_list:
            yield opentelemetry.trace.INVALID_SPAN
            return

        try:
            if current_trace_data is None:
                trace_to_close_in_finally_block = trace.TraceData(
                    name=name
                )
                opik.context_storage.set_trace_data(trace_to_close_in_finally_block)
                
            else:
                if current_span_data is not None and current_span_data.type == "llm":
                    span_to_close_in_finally_block = current_span_data
                    # LLM span was created externally in ADK tracer, we don't need to create a new one.
                    yield opentelemetry.trace.INVALID_SPAN
                    return

                start_span_arguments = arguments_helpers.StartSpanParameters(
                    name=name,
                    type="general",
                )

                _, span_to_close_in_finally_block = span_creation_handler.create_span_respecting_context(
                    start_span_arguments=start_span_arguments,
                    distributed_trace_headers=None,
                )
                opik.context_storage.add_span_data(span_to_close_in_finally_block)

            yield opentelemetry.trace.INVALID_SPAN
        finally:
            if trace_to_close_in_finally_block is not None:
                opik.context_storage.pop_trace_data(ensure_id=trace_to_close_in_finally_block.id)
                trace_to_close_in_finally_block.init_end_time()
                self._opik_client.trace(**trace_to_close_in_finally_block.as_parameters)
            else:
                assert span_to_close_in_finally_block is not None
                opik.context_storage.trim_span_data_stack_to_certain_span(span_to_close_in_finally_block.id)
                opik.context_storage.pop_span_data(ensure_id=span_to_close_in_finally_block.id)
                span_to_close_in_finally_block.init_end_time()
                self._opik_client.span(**span_to_close_in_finally_block.as_parameters)

