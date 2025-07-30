from typing import Any, Optional, Dict, Union
from agents import tracing

import logging

from opik.api_objects.span import span_data
from opik.api_objects.trace import trace_data
from opik.api_objects import opik_client
from opik.decorator import span_creation_handler, arguments_helpers
import opik.decorator.tracing_runtime_config as tracing_runtime_config
import opik.context_storage as context_storage

from . import span_data_parsers

LOGGER = logging.Logger(__name__)

OPENAI_SPAN_TYPES_WITH_MEANINGFUL_INPUT_OUTPUT = ["response", "generation", "custom"]


class OpikTracingProcessor(tracing.TracingProcessor):
    def __init__(
        self,
        project_name: Optional[str] = None,
        **kwargs: Any,
    ) -> None:
        super().__init__(**kwargs)
        # NOTE: The OpikTracingProcessor maps openai traces/spans to
        # opik traces/spans. Openai traces may be logged as opik spans.
        self._opik_spans_data_map: Dict[str, span_data.SpanData] = {}
        """Map from openai span id to the opik span data created for it."""
        self._created_opik_traces_data_map: Dict[str, trace_data.TraceData] = {}
        """Map from openai trace id to the opik trace data created for it."""

        self._project_name = project_name

        self._opik_client = opik_client.get_client_cached()

        self._openai_trace_id_to_first_meaningful_input: Dict[str, Dict[str, Any]] = {}
        self._openai_trace_id_to_last_meaningful_output: Dict[str, Dict[str, Any]] = {}
        """
        Used to populate opik span/trace corresponding to the openai trace with meaningful inputs and outputs.
        By default inputs and outputs in the openai trace are always empty and it is harder to review in Opik UI.
        """

        self._openai_trace_id_to_external_opik_parent_span_id: Dict[
            str, Optional[str]
        ] = {}

        self._opik_context_storage = context_storage.get_current_context_instance()

    def force_flush(self) -> bool:
        return self._opik_client.flush()

    def shutdown(self) -> None:
        self.force_flush()

    def on_trace_start(self, trace: tracing.Trace) -> None:
        try:
            trace_metadata = trace.metadata or {}
            trace_metadata["created_from"] = "openai-agents"
            if trace.trace_id:
                trace_metadata["agents-trace-id"] = trace.trace_id

            current_trace = self._opik_context_storage.get_trace_data()
            if current_trace is None:
                current_trace = trace_data.TraceData(
                    name=trace.name,
                    project_name=self._project_name,
                    metadata=trace_metadata,
                    thread_id=trace.group_id,
                )
                self._opik_context_storage.set_trace_data(current_trace)
                self._created_opik_traces_data_map[trace.trace_id] = current_trace
                if (
                    self._opik_client.config.log_start_trace_span
                    and tracing_runtime_config.is_tracing_active()
                ):
                    self._opik_client.trace(**current_trace.as_start_parameters)
            else:
                start_span_arguments = arguments_helpers.StartSpanParameters(
                    name=trace.name,
                    project_name=self._project_name,
                    metadata=trace_metadata,
                    type="general",
                )
                _, opik_span_data = (
                    span_creation_handler.create_span_respecting_context(
                        start_span_arguments=start_span_arguments,
                        distributed_trace_headers=None,
                        opik_context_storage=self._opik_context_storage,
                    )
                )
                self._opik_spans_data_map[trace.trace_id] = opik_span_data
                self._opik_context_storage.add_span_data(opik_span_data)
                self._openai_trace_id_to_external_opik_parent_span_id[
                    trace.trace_id
                ] = opik_span_data.parent_span_id

                if (
                    self._opik_client.config.log_start_trace_span
                    and tracing_runtime_config.is_tracing_active()
                ):
                    self._opik_client.span(**opik_span_data.as_start_parameters)

        except Exception:
            LOGGER.error("on_trace_start failed", exc_info=True)

    def on_trace_end(self, trace: tracing.Trace) -> None:
        try:
            opik_trace_or_span_data = self._try_get_span_or_trace(trace.trace_id)
            if opik_trace_or_span_data is None:
                LOGGER.error(
                    f"on_trace_end failed: no opik span/trace found for openai trace {trace.trace_id}. Probably due to an error in the previous callback execution"
                )
                return

            opik_trace_or_span_data.init_end_time()

            self._populate_root_span_or_trace_with_meaningful_input_and_output(
                trace.trace_id, opik_trace_or_span_data
            )
            if isinstance(opik_trace_or_span_data, trace_data.TraceData):
                if tracing_runtime_config.is_tracing_active():
                    self._opik_client.trace(**opik_trace_or_span_data.as_parameters)
            else:
                if tracing_runtime_config.is_tracing_active():
                    self._opik_client.span(**opik_trace_or_span_data.as_parameters)
        except Exception:
            LOGGER.error("on_trace_end failed", exc_info=True)
        finally:
            self._try_finalize_openai_trace(trace.trace_id)

    def on_span_start(self, span: tracing.Span[Any]) -> None:
        try:
            parsed_span_data = span_data_parsers.parse_spandata(span.span_data)

            opik_span_or_trace_data = (
                self._try_get_span_or_trace(span.parent_id)
                if span.parent_id is not None
                else self._try_get_span_or_trace(span.trace_id)
            )
            assert opik_span_or_trace_data is not None
            opik_span_data = opik_span_or_trace_data.create_child_span_data(
                name=parsed_span_data.name,
                type=parsed_span_data.type,
                input=parsed_span_data.input,
                output=parsed_span_data.output,
                metadata=parsed_span_data.metadata,
                usage=parsed_span_data.usage,
                model=parsed_span_data.model,
                provider=parsed_span_data.provider,
            )

            self._opik_context_storage.add_span_data(opik_span_data)
            self._opik_spans_data_map[span.span_id] = opik_span_data

            if (
                self._opik_client.config.log_start_trace_span
                and tracing_runtime_config.is_tracing_active()
            ):
                self._opik_client.span(**opik_span_data.as_start_parameters)

        except Exception:
            LOGGER.error("on_span_start failed", exc_info=True)

    def on_span_end(self, span: tracing.Span[Any]) -> None:
        try:
            parsed_span_data = span_data_parsers.parse_spandata(span.span_data)

            opik_span_data = self._opik_spans_data_map[span.span_id]
            opik_span_data.init_end_time().update(**parsed_span_data.__dict__)

            if tracing_runtime_config.is_tracing_active():
                self._opik_client.span(**opik_span_data.as_parameters)

            if (
                span.span_data.type
                not in OPENAI_SPAN_TYPES_WITH_MEANINGFUL_INPUT_OUTPUT
            ):
                return

            if (
                opik_span_data.input is not None
                and span.trace_id not in self._openai_trace_id_to_first_meaningful_input
            ):
                self._openai_trace_id_to_first_meaningful_input[span.trace_id] = (
                    opik_span_data.input
                )

            if opik_span_data.output is not None:
                self._openai_trace_id_to_last_meaningful_output[span.trace_id] = (
                    opik_span_data.output
                )

        except Exception:
            LOGGER.error("on_span_end failed", exc_info=True)
        finally:
            self._try_finalize_openai_span(span.span_id)

    def _try_get_span_or_trace(
        self, id: str
    ) -> Union[span_data.SpanData, trace_data.TraceData, None]:
        return self._opik_spans_data_map.get(
            id
        ) or self._created_opik_traces_data_map.get(id)

    def _populate_root_span_or_trace_with_meaningful_input_and_output(
        self,
        openai_trace_id: str,
        opik_trace_or_span_data: Union[trace_data.TraceData, span_data.SpanData],
    ) -> None:
        first_meaningful_input = self._openai_trace_id_to_first_meaningful_input.get(
            openai_trace_id
        )
        last_meaningful_output = self._openai_trace_id_to_last_meaningful_output.get(
            openai_trace_id
        )

        if first_meaningful_input is not None:
            opik_trace_or_span_data.update(input=first_meaningful_input)

        if last_meaningful_output is not None:
            opik_trace_or_span_data.update(output=last_meaningful_output)

    def _try_finalize_openai_trace(self, openai_trace_id: str) -> None:
        if openai_trace_id in self._opik_spans_data_map:
            opik_span_data = self._opik_spans_data_map[openai_trace_id]
            self._opik_context_storage.pop_span_data(ensure_id=opik_span_data.id)
            self._opik_spans_data_map.pop(openai_trace_id)
        elif openai_trace_id in self._created_opik_traces_data_map:
            opik_trace_data = self._created_opik_traces_data_map[openai_trace_id]
            self._opik_context_storage.pop_trace_data(ensure_id=opik_trace_data.id)
            self._created_opik_traces_data_map.pop(openai_trace_id)

        root_external_parent_span_id = (
            self._openai_trace_id_to_external_opik_parent_span_id.pop(
                openai_trace_id, None
            )
        )
        if root_external_parent_span_id is not None:
            # ensure no hanging spans left from the OpikTracingProcessor
            self._opik_context_storage.trim_span_data_stack_to_certain_span(
                root_external_parent_span_id
            )

        self._openai_trace_id_to_first_meaningful_input.pop(openai_trace_id, None)
        self._openai_trace_id_to_last_meaningful_output.pop(openai_trace_id, None)

    def _try_finalize_openai_span(self, openai_span_id: str) -> None:
        if openai_span_id in self._opik_spans_data_map:
            opik_span_data = self._opik_spans_data_map[openai_span_id]
            self._opik_context_storage.pop_span_data(ensure_id=opik_span_data.id)
            self._opik_spans_data_map.pop(openai_span_id)
