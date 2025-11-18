import logging
from typing import Optional, Dict, List, Any
import uuid

from llama_index.core.callbacks import schema as llama_index_schema
from llama_index.core.callbacks import base_handler

import opik.context_storage as context_storage
import opik.decorator.tracing_runtime_config as tracing_runtime_config
import opik.decorator.arguments_helpers as arguments_helpers
import opik.decorator.span_creation_handler as span_creation_handler
from opik.api_objects import opik_client, span, trace

from . import event_parsing_utils

LOGGER = logging.getLogger(__name__)


INDEX_CONSTRUCTION_TRACE_NAME = "index_construction"


def _get_last_event(trace_map: Dict[str, List[str]]) -> str:
    def dfs(key: str) -> str:
        if key not in trace_map or not trace_map[key]:
            return key
        return dfs(trace_map[key][-1])

    start_key = next(iter(trace_map))
    return dfs(start_key)


class LlamaIndexCallbackHandler(base_handler.BaseCallbackHandler):
    def __init__(
        self,
        event_starts_to_ignore: Optional[List[llama_index_schema.CBEventType]] = None,
        event_ends_to_ignore: Optional[List[llama_index_schema.CBEventType]] = None,
        project_name: Optional[str] = None,
        skip_index_construction_trace: bool = False,
    ):
        """
        Initialize the instance with optional customization to define event filters and project-
        specific data handling. The constructor sets up the necessary client and data mappings
        for operational processing.

        Parameters:
            event_starts_to_ignore: Optional list of event start types to be ignored during
                processing.
            event_ends_to_ignore: Optional list of event end types to be ignored during
                processing.
            project_name: Optional string representing the project name to establish context in
                client operations.
            skip_index_construction_trace: A boolean value determining whether to skip creation of trace/spans of index
                construction.
        """
        event_starts_to_ignore = (
            event_starts_to_ignore if event_starts_to_ignore else []
        )
        event_ends_to_ignore = event_ends_to_ignore if event_ends_to_ignore else []
        super().__init__(
            event_starts_to_ignore=event_starts_to_ignore,
            event_ends_to_ignore=event_ends_to_ignore,
        )

        self._skip_index_construction_trace = skip_index_construction_trace
        self._project_name = project_name
        self._opik_client = opik_client.get_client_cached()

        self._opik_context_storage = context_storage.get_current_context_instance()

        self._opik_trace_data: Optional[trace.TraceData] = None
        self._trace_created_by_us = False
        self._wrapper_span_data: Optional[span.SpanData] = None
        self._llama_index_root_name: Optional[str] = None

        self._map_event_id_to_span_data: Dict[str, span.SpanData] = {}
        self._map_event_id_to_output: Dict[str, Any] = {}

    def _create_trace_data(self, trace_name: Optional[str]) -> trace.TraceData:
        trace_data = trace.TraceData(
            name=trace_name,
            metadata={"created_from": "llama_index"},
            project_name=self._project_name,
        )

        if (
            self._opik_client.config.log_start_trace_span
            and tracing_runtime_config.is_tracing_active()
        ):
            self._opik_client.trace(**trace_data.as_start_parameters)

        return trace_data

    def start_trace(self, trace_id: Optional[str] = None) -> None:
        if (
            self._skip_index_construction_trace
            and trace_id == INDEX_CONSTRUCTION_TRACE_NAME
        ):
            return

        self._llama_index_root_name = trace_id if trace_id else "llama_index_operation"

        span_creation_result = span_creation_handler.create_span_respecting_context(
            start_span_arguments=arguments_helpers.StartSpanParameters(
                name=self._llama_index_root_name,
                type="general",
                project_name=self._project_name,
                metadata={"created_from": "llama_index"},
            ),
            distributed_trace_headers=None,
            opik_context_storage=self._opik_context_storage,
        )

        if span_creation_result.trace_data is not None:
            self._opik_trace_data = span_creation_result.trace_data
            self._trace_created_by_us = True
            self._opik_context_storage.set_trace_data(self._opik_trace_data)
            self._opik_client.trace(**self._opik_trace_data.as_start_parameters)
            # ignore span creation result to avoid duplicating the trace
        else:
            self._wrapper_span_data = span_creation_result.span_data
            self._opik_context_storage.add_span_data(self._wrapper_span_data)
            self._opik_client.span(**self._wrapper_span_data.as_start_parameters)

    def end_trace(
        self,
        trace_id: Optional[str] = None,
        trace_map: Optional[Dict[str, List[str]]] = None,
    ) -> None:
        if not trace_map:
            return

        # When a trace finishes, we first get the last event output
        last_event = _get_last_event(trace_map)
        last_event_output = self._map_event_id_to_output.get(last_event, None)

        # If we created a wrapper span (external trace existed), finalize it
        if self._wrapper_span_data is not None:
            self._wrapper_span_data.init_end_time().update(output=last_event_output)
            self._opik_client.span(**self._wrapper_span_data.as_parameters)

            self._opik_context_storage.pop_span_data(
                ensure_id=self._wrapper_span_data.id
            )
            self._wrapper_span_data = None

        # And then end the trace with the optional output
        if self._opik_trace_data is not None:
            # Only finalize the trace if we created it
            if self._trace_created_by_us:
                self._opik_trace_data.init_end_time().update(output=last_event_output)
                self._opik_client.trace(**self._opik_trace_data.as_parameters)

                # Pop trace from context since we created it
                self._opik_context_storage.pop_trace_data(
                    ensure_id=self._opik_trace_data.id
                )

            self._opik_trace_data = None
            self._trace_created_by_us = False

        # Clear the root name
        self._llama_index_root_name = None

        # Do not clean _map_event_id_to_span_data as streaming LLM events can
        # end after this method is called. _map_event_id_to_span_data is
        # individually cleaned after each event is ended
        self._map_event_id_to_output.clear()

    def on_event_start(
        self,
        event_type: llama_index_schema.CBEventType,
        payload: Optional[Dict[str, Any]] = None,
        event_id: Optional[str] = None,
        parent_id: Optional[str] = None,
        **kwargs: Any,
    ) -> str:
        if not event_id:
            event_id = str(uuid.uuid4())

        if self._opik_trace_data is None and self._wrapper_span_data is None:
            if not self._skip_index_construction_trace:
                LOGGER.warning(
                    "No trace data found in context for event start. "
                    "This is likely due to the fact that the trace is not started properly. "
                    f"The parent_id: {parent_id}, event_type: {event_type}, event_id: {event_id}."
                )

            return event_id

        span_input = event_parsing_utils.get_span_input_from_events(event_type, payload)

        event_duplicates_root_operation = (
            parent_id == llama_index_schema.BASE_TRACE_EVENT
            and event_type.value == self._llama_index_root_name
        )
        if event_duplicates_root_operation:
            if span_input:
                if self._wrapper_span_data is not None:
                    self._wrapper_span_data.update(input=span_input)
                elif self._opik_trace_data is not None:
                    self._opik_trace_data.update(input=span_input)
            return event_id

        span_creation_result = span_creation_handler.create_span_respecting_context(
            start_span_arguments=arguments_helpers.StartSpanParameters(
                name=event_type.value,
                input=span_input,
                type=(
                    "llm"
                    if event_type == llama_index_schema.CBEventType.LLM
                    else "general"
                ),
                project_name=self._project_name,
                metadata={"created_from": "llama_index"},
            ),
            distributed_trace_headers=None,
            opik_context_storage=self._opik_context_storage,
        )
        span_data = span_creation_result.span_data
        self._map_event_id_to_span_data[event_id] = span_data

        # Add span to context storage
        self._opik_context_storage.add_span_data(span_data)

        if (
            self._opik_client.config.log_start_trace_span
            and tracing_runtime_config.is_tracing_active()
        ):
            self._opik_client.span(**span_data.as_start_parameters)

        parent_event_is_root_operation = (
            parent_id == llama_index_schema.BASE_TRACE_EVENT
        )
        if parent_event_is_root_operation and span_input is not None:
            if self._wrapper_span_data is not None:
                self._wrapper_span_data.update(input=span_input)
            elif self._opik_trace_data is not None:
                self._opik_trace_data.update(input=span_input)

        return event_id

    def on_event_end(
        self,
        event_type: llama_index_schema.CBEventType,
        payload: Optional[Dict[str, Any]] = None,
        event_id: Optional[str] = None,
        **kwargs: Any,
    ) -> None:
        span_output = event_parsing_utils.get_span_output_from_event(
            event_type, payload
        )

        error_info = event_parsing_utils.get_span_error_info(payload)

        if event_id:
            self._map_event_id_to_output[event_id] = span_output

            # Log the output to the span with the matching id
            if event_id in self._map_event_id_to_span_data:
                span_data = self._map_event_id_to_span_data[event_id]

                llm_usage_info = event_parsing_utils.get_usage_data(payload)
                span_data.update(**llm_usage_info.__dict__)

                span_data.update(
                    output=span_output, error_info=error_info
                ).init_end_time()
                if tracing_runtime_config.is_tracing_active():
                    self._opik_client.span(**span_data.as_parameters)

                self._opik_context_storage.pop_span_data(ensure_id=span_data.id)

                del self._map_event_id_to_span_data[event_id]

    def flush(self) -> None:
        """Sends pending Opik data to the backend"""
        self._opik_client.flush()
