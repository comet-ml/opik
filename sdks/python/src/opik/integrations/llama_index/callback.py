import contextvars
import logging
from typing import Optional, Dict, Any, List, Union
import uuid

from llama_index.core.callbacks import schema as llama_index_schema
from llama_index.core.callbacks import base_handler

from opik import context_storage, tracing_runtime_config
from opik.decorator import arguments_helpers, span_creation_handler
from opik.api_objects import opik_client, span, trace

from . import event_parsing_utils

LOGGER = logging.getLogger(__name__)

INDEX_CONSTRUCTION_TRACE_NAME = "index_construction"
LLAMA_INDEX_METADATA = {"created_from": "llama_index"}

# Context variable for root trace/span created by LlamaIndex
_llama_root: contextvars.ContextVar[Optional[Union[span.SpanData, trace.TraceData]]] = (
    contextvars.ContextVar("_llama_root", default=None)
)


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
        """Initialize LlamaIndex callback handler for Opik tracing.

        Args:
            event_starts_to_ignore: Event start types to be ignored during processing.
            event_ends_to_ignore: Event end types to be ignored during processing.
            project_name: Project name for trace/span context.
            skip_index_construction_trace: Whether to skip index construction traces.
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

        # Event tracking - shared across contexts, but events have unique IDs
        self._map_event_id_to_span_data: Dict[str, span.SpanData] = {}
        self._map_event_id_to_output: Dict[str, Any] = {}

        # For streaming: end_trace may be called before event_end, so we need to
        # defer the trace output update until the event output is available
        self._pending_root_output_updates: Dict[
            str, Union[span.SpanData, trace.TraceData]
        ] = {}

    def _send_root_to_backend(
        self, root: Union[span.SpanData, trace.TraceData]
    ) -> None:
        """Send root trace or span data to the backend."""
        if isinstance(root, span.SpanData):
            self._opik_client.span(**root.as_parameters)
        elif isinstance(root, trace.TraceData):
            self._opik_client.trace(**root.as_parameters)

    def start_trace(self, trace_id: Optional[str] = None) -> None:
        if (
            self._skip_index_construction_trace
            and trace_id == INDEX_CONSTRUCTION_TRACE_NAME
        ):
            return

        trace_name = trace_id if trace_id else "llama_index_operation"

        span_creation_result = span_creation_handler.create_span_respecting_context(
            start_span_arguments=arguments_helpers.StartSpanParameters(
                name=trace_name,
                type="general",
                project_name=self._project_name,
                metadata=LLAMA_INDEX_METADATA,
            ),
            distributed_trace_headers=None,
            opik_context_storage=self._opik_context_storage,
        )

        if span_creation_result.trace_data is not None:
            self._opik_context_storage.set_trace_data(span_creation_result.trace_data)
            self._opik_client.trace(
                **span_creation_result.trace_data.as_start_parameters
            )
            _llama_root.set(span_creation_result.trace_data)
        else:
            self._opik_context_storage.add_span_data(span_creation_result.span_data)
            self._opik_client.span(**span_creation_result.span_data.as_start_parameters)
            _llama_root.set(span_creation_result.span_data)

    def end_trace(
        self,
        trace_id: Optional[str] = None,
        trace_map: Optional[Dict[str, List[str]]] = None,
    ) -> None:
        if not trace_map:
            return

        root = _llama_root.get()
        if root is None:
            return

        last_event = _get_last_event(trace_map)

        # Check if the output for the last event is already available.
        # For streaming calls, LlamaIndex calls end_trace() BEFORE event_end(),
        # so the output won't be stored yet.
        if last_event in self._map_event_id_to_output:
            last_event_output = self._map_event_id_to_output.get(last_event)
            root.init_end_time().update(output=last_event_output)

            # Send the trace/span with output
            self._send_root_to_backend(root)
        else:
            # Output not available yet (streaming scenario).
            # Store the root so we can update it when event_end is called.
            # Don't send the trace/span yet - it will be sent in on_event_end
            # with the output and correct end_time to avoid race conditions.
            # Note: We don't set end_time here because the actual end is when
            # the last event ends, not when LlamaIndex calls end_trace().
            self._pending_root_output_updates[last_event] = root

        # Clean up context storage
        if isinstance(root, span.SpanData):
            self._opik_context_storage.pop_span_data(ensure_id=root.id)
        elif isinstance(root, trace.TraceData):
            self._opik_context_storage.pop_trace_data(ensure_id=root.id)

        # Clean up
        _llama_root.set(None)

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

        root_span_or_trace = _llama_root.get()

        if root_span_or_trace is None:
            if not self._skip_index_construction_trace:
                LOGGER.warning(
                    "No active LlamaIndex trace/span found in context. "
                    "parent_id=%s, event_type=%s, event_id=%s",
                    parent_id,
                    event_type,
                    event_id,
                )
            return event_id

        span_input = event_parsing_utils.get_span_input_from_events(event_type, payload)

        # Skip creating span if event duplicates root operation name
        root_name = root_span_or_trace.name if root_span_or_trace else None
        event_duplicates_root = (
            parent_id == llama_index_schema.BASE_TRACE_EVENT
            and event_type.value == root_name
        )
        if event_duplicates_root:
            if span_input:
                root_span_or_trace.update(input=span_input)
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
                metadata=LLAMA_INDEX_METADATA,
            ),
            distributed_trace_headers=None,
            opik_context_storage=self._opik_context_storage,
        )
        span_data = span_creation_result.span_data
        self._map_event_id_to_span_data[event_id] = span_data
        self._opik_context_storage.add_span_data(span_data)

        if (
            self._opik_client.config.log_start_trace_span
            and tracing_runtime_config.is_tracing_active()
        ):
            self._opik_client.span(**span_data.as_start_parameters)

        # Update root input from first child event
        if parent_id == llama_index_schema.BASE_TRACE_EVENT and span_input is not None:
            root_span_or_trace.update(input=span_input)

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

        if not event_id:
            return

        # Store output for end_trace
        self._map_event_id_to_output[event_id] = span_output

        # Check if there's a pending root trace/span output update for this event.
        # This happens when end_trace() was called before event_end() (streaming scenario).
        if event_id in self._pending_root_output_updates:
            root = self._pending_root_output_updates.pop(event_id)
            # Set end_time now (the actual end) and update with output
            root.init_end_time().update(output=span_output)

            # Send the trace/span to the backend with correct end_time and output
            self._send_root_to_backend(root)

        # Finalize span if it exists
        if event_id in self._map_event_id_to_span_data:
            span_data = self._map_event_id_to_span_data[event_id]

            llm_usage_info = event_parsing_utils.get_usage_data(payload)
            span_data.update(**llm_usage_info.__dict__)
            span_data.update(output=span_output, error_info=error_info).init_end_time()

            if tracing_runtime_config.is_tracing_active():
                self._opik_client.span(**span_data.as_parameters)

            self._opik_context_storage.pop_span_data(ensure_id=span_data.id)
            del self._map_event_id_to_span_data[event_id]

    def flush(self) -> None:
        """Flush pending Opik data to backend."""
        self._opik_client.flush()
