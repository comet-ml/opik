import contextvars
import logging
from typing import Optional, Dict, Any, List, Literal
import uuid

from llama_index.core.callbacks import schema as llama_index_schema
from llama_index.core.callbacks import base_handler

import opik.context_storage as context_storage
import opik.decorator.tracing_runtime_config as tracing_runtime_config
import opik.decorator.arguments_helpers as arguments_helpers
import opik.decorator.span_creation_handler as span_creation_handler
from opik.api_objects import opik_client, span

from . import event_parsing_utils

LOGGER = logging.getLogger(__name__)

INDEX_CONSTRUCTION_TRACE_NAME = "index_construction"
LLAMA_INDEX_METADATA = {"created_from": "llama_index"}

# Context variables for root trace/span created by LlamaIndex
_llama_root_id: contextvars.ContextVar[Optional[str]] = contextvars.ContextVar(
    "_llama_root_id", default=None
)
_llama_root_type: contextvars.ContextVar[Optional[Literal["trace", "span"]]] = (
    contextvars.ContextVar("_llama_root_type", default=None)
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
            _llama_root_id.set(span_creation_result.trace_data.id)
            _llama_root_type.set("trace")
        else:
            self._opik_context_storage.add_span_data(span_creation_result.span_data)
            self._opik_client.span(**span_creation_result.span_data.as_start_parameters)
            _llama_root_id.set(span_creation_result.span_data.id)
            _llama_root_type.set("span")

    def _is_llama_index_root(self, data: Any) -> bool:
        """Check if trace/span data was created by LlamaIndex integration."""
        if data is None or data.metadata is None:
            return False
        return data.metadata.get("created_from") == "llama_index"

    def end_trace(
        self,
        trace_id: Optional[str] = None,
        trace_map: Optional[Dict[str, List[str]]] = None,
    ) -> None:
        if not trace_map:
            return

        root_id = _llama_root_id.get()
        root_type = _llama_root_type.get()
        if root_id is None or root_type is None:
            return

        last_event = _get_last_event(trace_map)
        last_event_output = self._map_event_id_to_output.get(last_event, None)

        if root_type == "span":
            # We created a wrapper span (external trace existed)
            top_span_data = self._opik_context_storage.top_span_data()
            if top_span_data is not None and top_span_data.id == root_id:
                top_span_data.init_end_time().update(output=last_event_output)
                self._opik_client.span(**top_span_data.as_parameters)
                self._opik_context_storage.pop_span_data(ensure_id=top_span_data.id)
        elif root_type == "trace":
            # We created a trace (no external trace existed)
            trace_data = self._opik_context_storage.get_trace_data()
            if trace_data is not None and trace_data.id == root_id:
                trace_data.init_end_time().update(output=last_event_output)
                self._opik_client.trace(**trace_data.as_parameters)
                self._opik_context_storage.pop_trace_data(ensure_id=trace_data.id)

        # Clean up
        _llama_root_id.set(None)
        _llama_root_type.set(None)

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

        # Get root trace/span from context
        top_span = self._opik_context_storage.top_span_data()
        root_trace = self._opik_context_storage.get_trace_data()

        # Determine which is our LlamaIndex root (wrapper span takes precedence)
        root_span = top_span if self._is_llama_index_root(top_span) else None
        if root_span is None:
            root_trace = root_trace if self._is_llama_index_root(root_trace) else None

        if root_trace is None and root_span is None:
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
        root_name = (
            root_trace.name if root_trace else (root_span.name if root_span else None)
        )
        event_duplicates_root = (
            parent_id == llama_index_schema.BASE_TRACE_EVENT
            and event_type.value == root_name
        )
        if event_duplicates_root:
            if span_input:
                if root_span is not None:
                    root_span.update(input=span_input)
                elif root_trace is not None:
                    root_trace.update(input=span_input)
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
            if root_span is not None:
                root_span.update(input=span_input)
            elif root_trace is not None:
                root_trace.update(input=span_input)

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
