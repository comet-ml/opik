import logging
from typing import Optional, Dict, List, Any
import uuid

from llama_index.core.callbacks import schema as llama_index_schema
from llama_index.core.callbacks import base_handler

import opik.opik_context as opik_context
import opik.context_storage as context_storage
import opik.decorator.tracing_runtime_config as tracing_runtime_config
from opik.api_objects import opik_client, span, trace

from . import event_parsing_utils
from ...api_objects import helpers

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
        self._trace_created_by_us: bool = False
        self._wrapper_span_data: Optional[span.SpanData] = None

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

        # When a new LLama Index trace is started, check if there's already a trace in context
        existing_trace_data = self._opik_context_storage.get_trace_data()
        if existing_trace_data is not None:
            # Use existing trace from context (e.g., from @opik.track decorator)
            self._opik_trace_data = existing_trace_data
            self._trace_created_by_us = False
            
            # Create a wrapper span for LlamaIndex operations within the external trace
            existing_span_data = self._opik_context_storage.top_span_data()
            parent_span_id = existing_span_data.id if existing_span_data is not None else None
            
            project_name = helpers.resolve_child_span_project_name(
                parent_project_name=self._opik_trace_data.project_name,
                child_project_name=self._project_name,
                show_warning=self._opik_trace_data.created_by != "evaluation",
            )
            
            self._wrapper_span_data = span.SpanData(
                trace_id=self._opik_trace_data.id,
                name=trace_id if trace_id else "llama_index_operation",
                parent_span_id=parent_span_id,
                type="general",
                project_name=project_name,
            )
            self._opik_context_storage.add_span_data(self._wrapper_span_data)
            
            if (
                self._opik_client.config.log_start_trace_span
                and tracing_runtime_config.is_tracing_active()
            ):
                self._opik_client.span(**self._wrapper_span_data.as_start_parameters)
        else:
            # Create a new trace and add it to context
            self._opik_trace_data = self._create_trace_data(trace_name=trace_id)
            self._opik_context_storage.set_trace_data(self._opik_trace_data)
            self._trace_created_by_us = True

            if (
                self._opik_client.config.log_start_trace_span
                and tracing_runtime_config.is_tracing_active()
            ):
                self._opik_client.trace(**self._opik_trace_data.as_start_parameters)

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
            if tracing_runtime_config.is_tracing_active():
                self._opik_client.span(**self._wrapper_span_data.as_parameters)
            
            # Pop wrapper span from context
            self._opik_context_storage.pop_span_data(ensure_id=self._wrapper_span_data.id)
            self._wrapper_span_data = None

        # And then end the trace with the optional output
        if self._opik_trace_data is not None:
            # Only finalize the trace if we created it
            if self._trace_created_by_us:
                self._opik_trace_data.init_end_time().update(output=last_event_output)
                if tracing_runtime_config.is_tracing_active():
                    self._opik_client.trace(**self._opik_trace_data.as_parameters)

                # Pop trace from context since we created it
                self._opik_context_storage.pop_trace_data(ensure_id=self._opik_trace_data.id)

            self._opik_trace_data = None
            self._trace_created_by_us = False

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

        # the event is not part of a trace probably because we are skipping the index construction trace
        if self._opik_trace_data is None:
            if not self._skip_index_construction_trace:
                LOGGER.warning(
                    "No trace data found in context for event start. "
                    "This is likely due to the fact that the trace is not started properly. "
                    f"The parent_id: {parent_id}, event_type: {event_type}, event_id: {event_id}."
                )

            return event_id

        # Get parent span Id if it exists
        if parent_id and parent_id in self._map_event_id_to_span_data:
            opik_parent_id = self._map_event_id_to_span_data[parent_id].id
        else:
            # If no parent within LlamaIndex event tree, use wrapper span if it exists,
            # otherwise check if there's an existing span in context (e.g., from @opik.track decorator)
            if self._wrapper_span_data is not None:
                opik_parent_id = self._wrapper_span_data.id
            else:
                current_span_data = self._opik_context_storage.top_span_data()
                opik_parent_id = current_span_data.id if current_span_data is not None else None

        # Compute the span input based on the event payload
        span_input = event_parsing_utils.get_span_input_from_events(event_type, payload)

        project_name = helpers.resolve_child_span_project_name(
            parent_project_name=self._opik_trace_data.project_name,
            child_project_name=self._project_name,
            show_warning=self._opik_trace_data.created_by != "evaluation",
        )

        # Create a new span for this event
        span_data = span.SpanData(
            trace_id=self._opik_trace_data.id,
            name=event_type.value,
            parent_span_id=opik_parent_id,
            type=(
                "llm" if event_type == llama_index_schema.CBEventType.LLM else "general"
            ),
            input=span_input,
            project_name=project_name,
        )
        self._map_event_id_to_span_data[event_id] = span_data

        # Add span to context storage
        self._opik_context_storage.add_span_data(span_data)

        if (
            self._opik_client.config.log_start_trace_span
            and tracing_runtime_config.is_tracing_active()
        ):
            self._opik_client.span(**span_data.as_start_parameters)

        # If the parent_id is a BASE_TRACE_EVENT, update the trace/wrapper span with the span input
        if parent_id == llama_index_schema.BASE_TRACE_EVENT and span_input:
            if self._wrapper_span_data is not None:
                self._wrapper_span_data.update(input=span_input)
            else:
                self._opik_trace_data.update(input=span_input)

        return event_id

    def on_event_end(
        self,
        event_type: llama_index_schema.CBEventType,
        payload: Optional[Dict[str, Any]] = None,
        event_id: Optional[str] = None,
        **kwargs: Any,
    ) -> None:
        # Get the span output from the event and store it so we can use it if needed
        # when finishing the trace
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

                # Remove span from context storage
                self._opik_context_storage.pop_span_data(ensure_id=span_data.id)

                del self._map_event_id_to_span_data[event_id]

    def flush(self) -> None:
        """Sends pending Opik data to the backend"""
        self._opik_client.flush()
