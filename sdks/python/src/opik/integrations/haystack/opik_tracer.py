import contextlib
import logging
import os
from typing import Any, Dict, Iterator, List, Optional, Union

import haystack
from haystack import dataclasses as haystack_dataclasses
from haystack import tracing
from haystack.tracing import utils as tracing_utils

import opik.url_helpers as url_helpers
import opik.context_storage as context_storage
import opik.decorator.tracing_runtime_config as tracing_runtime_config
import opik.exceptions as exceptions
from opik.api_objects import opik_client
from opik.api_objects import span as opik_span
from opik.api_objects import trace as opik_trace

from . import converters

LOGGER = logging.getLogger(__name__)

HAYSTACK_OPIK_ENFORCE_FLUSH_ENV_VAR = "HAYSTACK_OPIK_ENFORCE_FLUSH"
_SUPPORTED_GENERATORS = [
    "AzureOpenAIGenerator",
    "OpenAIGenerator",
    "AnthropicGenerator",
    "HuggingFaceAPIGenerator",
    "HuggingFaceLocalGenerator",
    "CohereGenerator",
]
_SUPPORTED_CHAT_GENERATORS = [
    "AzureOpenAIChatGenerator",
    "OpenAIChatGenerator",
    "AnthropicChatGenerator",
    "HuggingFaceAPIChatGenerator",
    "HuggingFaceLocalChatGenerator",
    "CohereChatGenerator",
]
_ALL_SUPPORTED_GENERATORS = _SUPPORTED_GENERATORS + _SUPPORTED_CHAT_GENERATORS


_PIPELINE_RUN_KEY = "haystack.pipeline.run"
_PIPELINE_INPUT_DATA_KEY = "haystack.pipeline.input_data"
_PIPELINE_OUTPUT_DATA_KEY = "haystack.pipeline.output_data"
_COMPONENT_NAME_KEY = "haystack.component.name"
_COMPONENT_TYPE_KEY = "haystack.component.type"
_COMPONENT_OUTPUT_KEY = "haystack.component.output"

# Removed custom context storage - now using opik.context_storage


class OpikSpanBridge(tracing.Span):
    """
    Internal class representing a bridge between the Haystack span tracing API and Opik.
    
    This class implements the Haystack Span interface and bridges the gap between
    Haystack's tracing system and Opik's tracing capabilities. It manages both
    SpanData and TraceData from Opik and provides the necessary methods for
    Haystack's tracing operations.
    """

    def __init__(self, span_or_trace_data: Union[opik_span.SpanData, opik_trace.TraceData]) -> None:
        """
        Initialize a OpikSpan instance.

        Args:
            span_or_trace_data: The SpanData or TraceData instance managed by Opik.
        """
        self._span_or_trace_data = span_or_trace_data
        # locally cache tags
        self._data: Dict[str, Any] = {}

    def set_tag(self, key: str, value: Any) -> None:
        """
        Set a generic tag for this span.

        Args:
            key: The tag key.
            value: The tag value.
        """
        coerced_value = tracing_utils.coerce_tag_value(value)
        self._span_or_trace_data.update(metadata={key: coerced_value, "created_from": "haystack"})
        self._data[key] = value

    def set_content_tag(self, key: str, value: Any) -> None:
        """
        Set a content-specific tag for this span.

        Args:
            key: The content tag key.
            value: The content tag value.
        """
        if not tracing.tracer.is_content_tracing_enabled:
            return
        
        if key.endswith(".input"):
            self._process_input_tag(value)
        elif key.endswith(".output"):
            self._process_output_tag(value)

        self._data[key] = value

    def _process_input_tag(self, value: Any) -> None:
        """
        Process input tag value and update span data.
        
        Args:
            value: The input tag value to process.
            
        Raises:
            OpikException: If message conversion fails.
        """
        try:
            if "messages" in value:
                messages = [
                    converters.convert_message_to_openai_format(message)
                    for message in value["messages"]
                ]
                self._span_or_trace_data.update(input={"input": messages})
            else:
                self._span_or_trace_data.update(input={"input": value})
        except Exception as e:
            LOGGER.error("Failed to process input tag: %s", e, exc_info=True)
            raise exceptions.OpikException(f"Failed to process input tag: {e}") from e

    def _process_output_tag(self, value: Any) -> None:
        """
        Process output tag value and update span data.
        
        Args:
            value: The output tag value to process.
            
        Raises:
            OpikException: If message conversion fails.
        """
        try:
            if "replies" in value:
                if all(
                    isinstance(reply, haystack_dataclasses.ChatMessage)
                    for reply in value["replies"]
                ):
                    replies = [
                        converters.convert_message_to_openai_format(message)
                        for message in value["replies"]
                    ]
                else:
                    replies = value["replies"]
                self._span_or_trace_data.update(output={"replies": replies})
            else:
                self._span_or_trace_data.update(output={"output": value})
        except Exception as e:
            LOGGER.error("Failed to process output tag: %s", e, exc_info=True)
            raise exceptions.OpikException(f"Failed to process output tag: {e}") from e

    def raw_span(self) -> Union[opik_span.SpanData, opik_trace.TraceData]:
        """
        Return the underlying span or trace data instance.

        :return: The Opik SpanData or TraceData instance.
        """
        return self._span_or_trace_data

    def get_correlation_data_for_logs(self) -> Dict[str, Any]:
        return {}
    
    def set_tags(self, tags: Dict[str, Any]) -> None:
        """
        Set multiple tags on this span.

        Args:
            tags: Dictionary of tag keys and values.
        """
        for key, value in tags.items():
            self.set_tag(key, value)


class OpikTracer(tracing.Tracer):
    """Bridge between Haystack tracer and Opik for tracing pipeline operations."""

    def __init__(self, opik_client: opik_client.Opik, name: str = "Haystack", project_name: Optional[str] = None) -> None:
        """
        Initialize OpikTracer.

        Args:
            opik_client: The Opik client instance.
            name: The name of the pipeline or component.
            project_name: The name of the project for tracing (optional).
        """
        if not tracing.tracer.is_content_tracing_enabled:
            LOGGER.warning(
                "Traces will not be logged to Opik because Haystack tracing is disabled. "
                "To enable, set the HAYSTACK_CONTENT_TRACING_ENABLED environment variable to true "
                "before importing Haystack."
            )
        self._opik_client = opik_client
        self._context: List[OpikSpanBridge] = []
        self._name = name
        self._project_name = project_name or opik_client.config.project_name
        self.enforce_flush = (
            os.getenv(HAYSTACK_OPIK_ENFORCE_FLUSH_ENV_VAR, "true").lower() == "true"
        )
        self._opik_context_storage = context_storage.get_current_context_instance()

    @contextlib.contextmanager
    def trace(
        self,
        operation_name: str,
        tags: Optional[Dict[str, Any]] = None,
        parent_span: Optional[OpikSpanBridge] = None,
    ) -> Iterator[tracing.Span]:
        tags = tags or {}
        span_name = tags.get(_COMPONENT_NAME_KEY, operation_name)
        
        # Create span based on context
        if parent_span:
            span = self._create_child_span(parent_span, span_name, operation_name, tags)
        else:
            span = self._create_span_or_trace(operation_name, span_name)

        self._context.append(span)
        
        # Set tags on the span data
        if tags:
            span.set_tags(tags)

        try:
            yield span
        finally:
            self._finalize_span(span, tags)

    def _create_span_or_trace(self, operation_name: str, span_name: str) -> OpikSpanBridge:
        """Create a span or trace based on existing context."""
        existing_trace_data = self._opik_context_storage.get_trace_data()
        existing_span_data = self._opik_context_storage.top_span_data()
        metadata = {"created_from": "haystack", "operation": operation_name}
        
        # If we have existing context, create a child span
        if existing_trace_data or existing_span_data:
            # For pipeline operations, use the pipeline name, otherwise use component name
            span_name_to_use = self._name if operation_name == _PIPELINE_RUN_KEY else span_name
            
            if existing_trace_data:
                # Create child span under existing trace
                span_data = existing_trace_data.create_child_span_data(
                    name=span_name_to_use,
                    type="general",
                    metadata=metadata
                )
            else:
                # Create child span under existing span
                span_data = existing_span_data.create_child_span_data(
                    name=span_name_to_use,
                    type="general",
                    metadata=metadata
                )
            
            self._opik_context_storage.add_span_data(span_data)
            return OpikSpanBridge(span_data)
        
        # Otherwise create a new trace
        trace_name = self._name if operation_name == _PIPELINE_RUN_KEY else span_name
        trace_data = opik_trace.TraceData(
            name=trace_name,
            metadata=metadata,
            project_name=self._project_name,
        )
        self._opik_context_storage.set_trace_data(trace_data)
        return OpikSpanBridge(trace_data)

    def _create_child_span(self, parent_span: OpikSpanBridge, span_name: str, operation_name: str, tags: Dict[str, Any]) -> OpikSpanBridge:
        """Create a child span from a parent span."""
        parent_data = parent_span.raw_span()
        span_type = "llm" if tags.get(_COMPONENT_TYPE_KEY) in _ALL_SUPPORTED_GENERATORS else "general"
        
        span_data = parent_data.create_child_span_data(
            name=span_name,
            type=span_type,
            metadata={"created_from": "haystack", "operation": operation_name}
        )
        self._opik_context_storage.add_span_data(span_data)
        return OpikSpanBridge(span_data)

    def _finalize_span(self, span: OpikSpanBridge, tags: Dict[str, Any]) -> None:
        """Finalize span by updating metadata and ending the span."""
        try:
            span_or_trace_data = span.raw_span()
            self._update_span_metadata(span, tags, span_or_trace_data)
            span_or_trace_data.init_end_time()
            
            # Send data to backend if tracing is active
            if tracing_runtime_config.is_tracing_active():
                if isinstance(span_or_trace_data, opik_trace.TraceData):
                    self._opik_client.trace(**span_or_trace_data.as_parameters)
                    # Only clear trace data if this is our own trace (not inherited)
                    current_trace = self._opik_context_storage.get_trace_data()
                    if current_trace and current_trace.id == span_or_trace_data.id:
                        self._opik_context_storage.set_trace_data(None)
                else:
                    self._opik_client.span(**span_or_trace_data.as_parameters)
                    # Remove span from context storage
                    current_span = self._opik_context_storage.top_span_data()
                    if current_span and current_span.id == span_or_trace_data.id:
                        self._opik_context_storage.pop_span_data()
            
            self._context.pop()
            if self.enforce_flush:
                self.flush()
        except Exception as e:
            LOGGER.error("Failed to finalize span: %s", e, exc_info=True)
            if self._context:
                self._context.pop()

    def _update_span_metadata(self, span: OpikSpanBridge, tags: Dict[str, Any], span_or_trace_data: Union[opik_span.SpanData, opik_trace.TraceData]) -> None:
        """Update span metadata based on component type."""
        component_type = tags.get(_COMPONENT_TYPE_KEY)
        
        # Update LLM metadata if it's a generator component
        if component_type in _ALL_SUPPORTED_GENERATORS:
            self._update_llm_metadata(span, span_or_trace_data, component_type)
        
        # Update pipeline metadata if available
        if tags.get(_PIPELINE_INPUT_DATA_KEY) is not None:
            input_data = tags.get("haystack.pipeline.input_data", {})
            output_data = tags.get("haystack.pipeline.output_data", {})
            span_or_trace_data.update(input=input_data, output=output_data)

    def _update_llm_metadata(self, span: OpikSpanBridge, span_or_trace_data: Union[opik_span.SpanData, opik_trace.TraceData], component_type: str) -> None:
        """Update metadata for LLM generator components."""
        try:
            if component_type in _SUPPORTED_GENERATORS:
                # Regular generators
                meta = span._data.get(_COMPONENT_OUTPUT_KEY, {}).get("meta")
                if meta and len(meta) > 0:
                    m = meta[0]
                    span_or_trace_data.update(usage=m.get("usage"), model=m.get("model"))
            elif component_type in _SUPPORTED_CHAT_GENERATORS:
                # Chat generators
                replies = span._data.get(_COMPONENT_OUTPUT_KEY, {}).get("replies")
                if replies and len(replies) > 0:
                    meta = replies[0].meta
                    span_or_trace_data.update(usage=meta.get("usage"), model=meta.get("model"))
        except (IndexError, AttributeError, KeyError) as e:
            LOGGER.warning("Failed to update LLM metadata for %s: %s", component_type, e)

    def flush(self) -> None:
        """Flush the Opik client to send pending data."""
        self._opik_client.flush()

    def current_span(self) -> Optional[OpikSpanBridge]:
        """Return the current active span."""
        return self._context[-1] if self._context else None

    def get_project_url(self) -> Optional[str]:
        """Return the URL to the tracing data."""
        span = self.current_span()
        if not span:
            return None

        span_data = span.raw_span()
        trace_id = span_data.id if isinstance(span_data, opik_trace.TraceData) else span_data.trace_id
        return url_helpers.get_project_url_by_trace_id(
            trace_id=trace_id, url_override=self._opik_client.config.url_override
        )

    def get_trace_id(self) -> Optional[str]:
        """Return the trace id of the current trace."""
        span = self.current_span()
        if not span:
            return None

        span_data = span.raw_span()
        return span_data.id if isinstance(span_data, opik_trace.TraceData) else span_data.trace_id
