import contextlib
import os
from typing import Any, Dict, Iterator, List, Optional, Union

from haystack import dataclasses as haystack_dataclasses
from haystack import logging, tracing
from haystack.tracing import utils as tracing_utils

import opik.url_helpers as url_helpers
from opik.api_objects import opik_client, span, trace
import opik.decorator.tracing_runtime_config as tracing_runtime_config
import opik.context_storage as context_storage

from . import converters

logger = logging.getLogger(__name__)

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
    """

    def __init__(self, span_or_trace_data: Union[span.SpanData, trace.TraceData]) -> None:
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
            if "messages" in value:
                messages = [
                    converters.convert_message_to_openai_format(message)
                    for message in value["messages"]
                ]
                self._span_or_trace_data.update(input={"input": messages})
            else:
                self._span_or_trace_data.update(input={"input": value})
        elif key.endswith(".output"):
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

        self._data[key] = value

    def raw_span(self) -> Union[span.SpanData, trace.TraceData]:
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
    """
    Internal class representing a bridge between the Haystack tracer and Opik.
    """

    def __init__(self, opik_client: opik_client.Opik, name: str = "Haystack") -> None:
        """
        Initialize a OpikTracer instance.

        Args:
            opik_client: The Opik client instance.
            name: The name of the pipeline or component. This name will be used to identify the tracing run on the
                Opik dashboard.
        """
        if not tracing.tracer.is_content_tracing_enabled:
            logger.warning(
                "Traces will not be logged to Opik because Haystack tracing is disabled. "
                "To enable, set the HAYSTACK_CONTENT_TRACING_ENABLED environment variable to true "
                "before importing Haystack."
            )
        self._opik_client = opik_client
        self._context: List[OpikSpanBridge] = []
        self._name = name
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
        
        # Check if we need to create a new trace or attach to existing context
        if not parent_span:
            if operation_name == _PIPELINE_RUN_KEY:
                # This is a pipeline run - check if there's an existing trace or span context
                existing_trace_data = self._opik_context_storage.get_trace_data()
                existing_span_data = self._opik_context_storage.top_span_data()
                
                if existing_trace_data is not None:
                    # Attach as a span to the existing trace
                    span_data = existing_trace_data.create_child_span_data(
                        name=self._name,
                        type="general",
                        metadata={"created_from": "haystack", "operation": operation_name}
                    )
                    self._opik_context_storage.add_span_data(span_data)
                    
                    if (
                        self._opik_client.config.log_start_trace_span
                        and tracing_runtime_config.is_tracing_active()
                    ):
                        self._opik_client.span(**span_data.as_start_parameters)
                    
                    span = OpikSpanBridge(span_data)
                elif existing_span_data is not None:
                    # Attach as a child span to the existing span
                    span_data = existing_span_data.create_child_span_data(
                        name=self._name,
                        type="general",
                        metadata={"created_from": "haystack", "operation": operation_name}
                    )
                    self._opik_context_storage.add_span_data(span_data)
                    
                    if (
                        self._opik_client.config.log_start_trace_span
                        and tracing_runtime_config.is_tracing_active()
                    ):
                        self._opik_client.span(**span_data.as_start_parameters)
                    
                    span = OpikSpanBridge(span_data)
                else:
                    # Create a new trace for the pipeline
                    trace_data = trace.TraceData(
                        name=self._name,
                        metadata={"created_from": "haystack", "operation": operation_name},
                        project_name=self._opik_client.config.project_name,
                    )
                    self._opik_context_storage.set_trace_data(trace_data)
                    
                    if (
                        self._opik_client.config.log_start_trace_span
                        and tracing_runtime_config.is_tracing_active()
                    ):
                        self._opik_client.trace(**trace_data.as_start_parameters)
                    
                    span = OpikSpanBridge(trace_data)
            else:
                logger.warning(
                    "Creating a new trace without a parent span is not recommended for operation '%s'.",
                    operation_name,
                )
                # Create a new trace for non-pipeline operations
                trace_data = trace.TraceData(
                    name=span_name,
                    metadata={"created_from": "haystack", "operation": operation_name},
                    project_name=self._opik_client.config.project_name,
                )
                self._opik_context_storage.set_trace_data(trace_data)
                
                if (
                    self._opik_client.config.log_start_trace_span
                    and tracing_runtime_config.is_tracing_active()
                ):
                    self._opik_client.trace(**trace_data.as_start_parameters)
                
                span = OpikSpanBridge(trace_data)
        else:
            # Create a child span
            parent_data = parent_span.raw_span()
            if isinstance(parent_data, trace.TraceData):
                span_data = parent_data.create_child_span_data(
                    name=span_name,
                    type="llm" if tags.get(_COMPONENT_TYPE_KEY) in _ALL_SUPPORTED_GENERATORS else "general",
                    metadata={"created_from": "haystack", "operation": operation_name}
                )
            else:
                span_data = parent_data.create_child_span_data(
                    name=span_name,
                    type="llm" if tags.get(_COMPONENT_TYPE_KEY) in _ALL_SUPPORTED_GENERATORS else "general",
                    metadata={"created_from": "haystack", "operation": operation_name}
                )
            
            self._opik_context_storage.add_span_data(span_data)
            
            if (
                self._opik_client.config.log_start_trace_span
                and tracing_runtime_config.is_tracing_active()
            ):
                self._opik_client.span(**span_data.as_start_parameters)
            
            span = OpikSpanBridge(span_data)

        self._context.append(span)
        
        # Set tags on the span data
        if tags:
            span.set_tags(tags)

        try:
            yield span
        finally:
            # Update span metadata based on component type
            span_or_trace_data = span.raw_span()
            
            if tags.get(_COMPONENT_TYPE_KEY) in _SUPPORTED_GENERATORS:
                # Haystack returns one meta dict for each message, but the 'usage' value
                # is always the same, let's just pick the first item
                meta = span._data.get(_COMPONENT_OUTPUT_KEY, {}).get("meta")
                if meta:
                    m = meta[0]
                    span_or_trace_data.update(usage=m.get("usage") or None, model=m.get("model"))
            elif tags.get(_COMPONENT_TYPE_KEY) in _SUPPORTED_CHAT_GENERATORS:
                replies = span._data.get(_COMPONENT_OUTPUT_KEY, {}).get("replies")
                if replies:
                    meta = replies[0].meta
                    span_or_trace_data.update(
                        usage=meta.get("usage") or None,
                        model=meta.get("model"),
                    )
            elif tags.get(_PIPELINE_INPUT_DATA_KEY) is not None:
                input_data = tags.get("haystack.pipeline.input_data", {})
                output_data = tags.get("haystack.pipeline.output_data", {})
                span_or_trace_data.update(
                    input=input_data,
                    output=output_data,
                )

            # End the span/trace
            span_or_trace_data.init_end_time()
            
            if tracing_runtime_config.is_tracing_active():
                if isinstance(span_or_trace_data, trace.TraceData):
                    self._opik_client.trace(**span_or_trace_data.as_parameters)
                    # Remove from context storage for traces
                    self._opik_context_storage.set_trace_data(None)
                else:
                    self._opik_client.span(**span_or_trace_data.as_parameters)
                    # Remove from context storage for spans
                    current_span = self._opik_context_storage.top_span_data()
                    if current_span and current_span.id == span_or_trace_data.id:
                        self._opik_context_storage.pop_span_data()
            
            self._context.pop()

            if self.enforce_flush:
                self.flush()

    def flush(self) -> None:
        self._opik_client.flush()

    def current_span(self) -> Optional[OpikSpanBridge]:
        """
        Return the current active span.

        Returns:
            The current span if available, else None.
        """
        return self._context[-1] if self._context else None

    def get_project_url(self) -> str:
        """
        Return the URL to the tracing data.

        Returns:
            The URL to the project that includes the tracing data.
        """
        last_span = self.current_span()

        if last_span is None:
            return ""

        span_or_trace_data = last_span.raw_span()
        trace_id = (
            span_or_trace_data.id
            if isinstance(span_or_trace_data, trace.TraceData)
            else span_or_trace_data.trace_id
        )

        return url_helpers.get_project_url_by_trace_id(
            trace_id=trace_id, url_override=self._opik_client.config.url_override
        )

    def get_trace_id(self) -> Optional[str]:
        """
        Return the trace id of the current trace.

        Returns:
            The id of the current trace.
        """
        last_span = self.current_span()

        if last_span is None:
            return None

        span_or_trace_data = last_span.raw_span()

        if isinstance(span_or_trace_data, trace.TraceData):
            return span_or_trace_data.id

        return span_or_trace_data.trace_id
