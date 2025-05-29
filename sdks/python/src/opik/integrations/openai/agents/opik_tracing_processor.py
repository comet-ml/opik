from typing import Any, Optional, Dict, Union
from agents import tracing

import logging
import contextvars

from opik.api_objects.span import span_data
from opik.api_objects.trace import trace_data
from opik.api_objects import opik_client
from opik.decorator import span_creation_handler, arguments_helpers
from opik import context_storage

from . import span_data_parsers

LOGGER = logging.Logger(__name__)


class OpikTracingProcessor(tracing.TracingProcessor):
    def __init__(
        self,
        project_name: Optional[str] = None,
        **kwargs: Any,
    ) -> None:
        super().__init__(**kwargs)
        self._span_data_map: Dict[str, span_data.SpanData] = {}
        """Map from openai span id to opik span data."""

        self._created_traces_data_map: Dict[str, trace_data.TraceData] = {}
        """Map from openai trace id to opik trace data."""

        self._project_name = project_name

        self._opik_client = opik_client.get_client_cached()
        self._opik_trace_id_to_first_span: Dict[str, span_data.SpanData] = {}
        self._opik_trace_id_to_last_llm_span: Dict[str, span_data.SpanData] = {}

        self._opik_context_storage = context_storage.get_current_context_instance()
        self._root_external_parent_span_id: contextvars.ContextVar[
            Optional[str]
        ] = contextvars.ContextVar("root_external_parent_span_id", default=None)


    def force_flush(self) -> bool:
        return self._opik_client.flush()

    def shutdown(self) -> None:
        self.force_flush()

    def on_trace_start(self, trace: tracing.Trace) -> None:
        trace_metadata = trace.metadata or {}
        trace_metadata["created_from"] = "openai-agents"
        if trace.trace_id:
            trace_metadata["agents-trace-id"] = trace.trace_id

        try:
            current_trace = self._opik_context_storage.get_trace_data()
            if current_trace is None:
                current_trace = trace_data.TraceData(
                    name=trace.name,
                    project_name=self._project_name,
                    metadata=trace_metadata,
                    thread_id=trace.group_id,
                )
                self._opik_context_storage.set_trace_data(current_trace)
                self._created_traces_data_map[trace.trace_id] = current_trace
            else:
                start_span_arguments = arguments_helpers.StartSpanParameters(
                    name=trace.name,
                    project_name=self._project_name,
                    metadata=trace_metadata,
                    type="general",
                )
                _, opik_span_data = span_creation_handler.create_span_respecting_context(
                    start_span_arguments=start_span_arguments,
                    distributed_trace_headers=None,
                    opik_context_storage=self._opik_context_storage,
                )
                self._span_data_map[trace.trace_id] = opik_span_data
                self._opik_context_storage.add_span_data(opik_span_data)
                self._root_external_parent_span_id.set(opik_span_data.parent_span_id)

        except Exception:
            LOGGER.debug("on_trace_start failed", exc_info=True)

    def on_trace_end(self, trace: tracing.Trace) -> None:
        try:
            opik_trace_or_span_data = self._try_get_span_or_trace(trace.trace_id)
            if opik_trace_or_span_data is None:
                return

            opik_trace_or_span_data.init_end_time()

            self._copy_input_and_output_from_child_spans(opik_trace_or_span_data)
            if isinstance(opik_trace_or_span_data, trace_data.TraceData):
                self._opik_client.trace(**opik_trace_or_span_data.__dict__)
            else:
                self._opik_client.span(**opik_trace_or_span_data.__dict__)
        except Exception:
            LOGGER.debug("on_trace_end failed", exc_info=True)
        finally:
            self._try_finalize_openai_trace(trace.trace_id)

    def on_span_start(self, span: tracing.Span[Any]) -> None:
        try:
            parsed_span_data = span_data_parsers.parse_spandata(span.span_data)
            assert False, "USE parsed span data!"
            _, opik_span_data = span_creation_handler.create_span_respecting_context(
                start_span_arguments=arguments_helpers.StartSpanParameters(
                    project_name=self._project_name,
                    name="placeholder-name",
                    type="general",
                ),
                distributed_trace_headers=None,
                opik_context_storage=self._opik_context_storage,
            )

            self._opik_context_storage.add_span_data(opik_span_data)
            self._span_data_map[span.span_id] = opik_span_data

            if (
                opik_span_data.trace_id not in self._opik_trace_id_to_first_span
            ) and span.span_data.type in ["response", "generation", "custom"]:
                self._opik_trace_id_to_first_span[opik_span_data.trace_id] = opik_span_data

        except Exception:
            LOGGER.debug("on_span_start failed", exc_info=True)

    def on_span_end(self, span: tracing.Span[Any]) -> None:
        try:
            parsed_span_data = span_data_parsers.parse_spandata(span.span_data)

            opik_span_data = self._span_data_map[span.span_id]
            opik_span_data.init_end_time().update(**parsed_span_data.__dict__)

            if span.span_data.type in ["response", "generation", "custom"]:
                self._opik_trace_id_to_last_llm_span[opik_span_data.trace_id] = (
                    opik_span_data
                )

            self._opik_client.span(**opik_span_data.__dict__)
        except Exception:
            LOGGER.debug("on_span_end failed", exc_info=True)
        finally:
            self._try_finalize_openai_span(span.span_id)
    
    def _try_get_span_or_trace(self, id: str) -> Union[span_data.SpanData, trace_data.TraceData, None]:
        return self._span_data_map.get(id) or self._created_traces_data_map.get(id)

    def _copy_input_and_output_from_child_spans(
        self, opik_trace_or_span_data: Union[trace_data.TraceData, span_data.SpanData]
    ) -> None:
        if opik_trace_or_span_data.id in self._opik_trace_id_to_first_span:
            opik_trace_or_span_data.update(
                input=self._opik_trace_id_to_first_span[opik_trace_or_span_data.id].input
            )

        if opik_trace_or_span_data.id in self._opik_trace_id_to_last_llm_span:
            opik_trace_or_span_data.update(
                output=self._opik_trace_id_to_last_llm_span[opik_trace_or_span_data.id].output
            )
    
    def _try_finalize_openai_trace(self, openai_trace_id: str) -> None:
        if openai_trace_id in self._span_data_map:
            opik_span_data = self._span_data_map[openai_trace_id]
            self._opik_context_storage.pop_span_data(ensure_id=opik_span_data.id)
        elif openai_trace_id in self._created_traces_data_map:
            opik_trace_data = self._created_traces_data_map[openai_trace_id]
            self._opik_context_storage.pop_trace_data(ensure_id=opik_trace_data.id)
        
        root_external_parent_span_id = self._root_external_parent_span_id.get()
        if root_external_parent_span_id is not None:
            self._opik_context_storage.trim_span_data_stack_to_certain_span(root_external_parent_span_id)
            self._root_external_parent_span_id.set(None)

    def _try_finalize_openai_span(self, openai_span_id: str) -> None:
        if openai_span_id in self._span_data_map:
            opik_span_data = self._span_data_map[openai_span_id]
            self._opik_context_storage.pop_span_data(ensure_id=opik_span_data.id)
        