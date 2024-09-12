from typing import Optional, Dict, List, Any
import uuid

from llama_index.core.callbacks import schema as llama_index_schema
from llama_index.core.callbacks import base_handler

from opik import opik_context
from opik.api_objects import opik_client, span, trace

from . import event_parsing_utils


class LlamaIndexCallbackHandler(base_handler.BaseCallbackHandler):
    def __init__(
        self,
        event_starts_to_ignore: Optional[List[llama_index_schema.CBEventType]] = None,
        event_ends_to_ignore: Optional[List[llama_index_schema.CBEventType]] = None,
    ):
        event_starts_to_ignore = (
            event_starts_to_ignore if event_starts_to_ignore else []
        )
        event_ends_to_ignore = event_ends_to_ignore if event_ends_to_ignore else []
        super().__init__(
            event_starts_to_ignore=event_starts_to_ignore,
            event_ends_to_ignore=event_ends_to_ignore,
        )

        self._opik_client = opik_client.get_client_cached()
        self._opik_trace_data: Optional[trace.TraceData] = None

        self._map_event_id_to_span_data: Dict[str, span.SpanData] = {}
        self._map_event_id_to_output: Dict[str, Any] = {}

    def _create_trace_data(self, trace_name: Optional[str]) -> trace.TraceData:
        trace_data = trace.TraceData(
            name=trace_name, metadata={"created_from": "llama_index"}
        )
        return trace_data

    def start_trace(self, trace_id: Optional[str] = None) -> None:
        # When a new LLama Index trace is started, create a new trace in Opik
        existing_trace_data = opik_context.get_current_trace_data()
        if existing_trace_data:
            self._opik_trace_data = existing_trace_data
        else:
            self._opik_trace_data = self._create_trace_data(trace_name=trace_id)

    def _get_last_event(self, trace_map: Dict[str, List[str]]) -> str:
        def dfs(key: str) -> str:
            if key not in trace_map or not trace_map[key]:
                return key
            return dfs(trace_map[key][-1])

        start_key = next(iter(trace_map))
        return dfs(start_key)

    def end_trace(
        self,
        trace_id: Optional[str] = None,
        trace_map: Optional[Dict[str, List[str]]] = None,
    ) -> None:
        if not trace_map:
            return

        # When a trace finishes, we first get the last event output
        last_event = self._get_last_event(trace_map)
        last_event_output = self._map_event_id_to_output.get(last_event, None)

        # And then end the trace with the optional output
        if self._opik_trace_data:
            self._opik_trace_data.init_end_time().update(output=last_event_output)
            self._opik_client.trace(**self._opik_trace_data.__dict__)
            self._opik_trace_data = None

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

        # Under some scenarios, it is possible for `start_trace` to not be called (for example if
        # the callback raises an exception in a previous call).
        # Unclear what the best behavior is here, so for now we'll just create a new trace when
        if self._opik_trace_data is None:
            self._opik_trace_data = self._create_trace_data(trace_name=parent_id)

        # Get parent span Id if it exists
        if parent_id and parent_id in self._map_event_id_to_span_data:
            opik_parent_id = self._map_event_id_to_span_data[parent_id].id
        else:
            opik_parent_id = None

        # Compute the span input based on the event payload
        span_input = event_parsing_utils.get_span_input_from_events(event_type, payload)

        # Create a new span for this event
        span_data = span.SpanData(
            trace_id=self._opik_trace_data.id,
            name=event_type.value,
            parent_span_id=opik_parent_id,
            type=(
                "llm" if event_type == llama_index_schema.CBEventType.LLM else "general"
            ),
            input=span_input,
        )
        self._map_event_id_to_span_data[event_id] = span_data

        # If the parent_id is a BASE_TRACE_EVENT, update the trace with the span input
        if parent_id == llama_index_schema.BASE_TRACE_EVENT and span_input:
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
        if event_id:
            self._map_event_id_to_output[event_id] = span_output

            # Log the output to the span with the matching id
            if event_id in self._map_event_id_to_span_data:
                span_data = self._map_event_id_to_span_data[event_id]
                span_data.update(output=span_output).init_end_time()
                self._opik_client.span(**span_data.__dict__)

    def flush(self) -> None:
        """Sends pending Opik data to the backend"""
        self._opik_client.flush()
