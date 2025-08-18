import logging
from typing import Any, Dict, Union

from haystack import dataclasses as haystack_dataclasses
from haystack import tracing
from haystack.tracing import utils as tracing_utils

import opik.exceptions as exceptions
from opik.api_objects import span as opik_span
from opik.api_objects import trace as opik_trace

from . import converters
from . import constants

LOGGER = logging.getLogger(__name__)


class OpikSpanBridge(tracing.Span):
    """
    Internal class representing a bridge between the Haystack span tracing API and Opik.

    This class implements the Haystack Span interface and bridges the gap between
    Haystack's tracing system and Opik's tracing capabilities. It manages both
    SpanData and TraceData from Opik and provides the necessary methods for
    Haystack's tracing operations.
    """

    def __init__(
        self, span_or_trace_data: Union[opik_span.SpanData, opik_trace.TraceData]
    ) -> None:
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
        self._span_or_trace_data.update(
            metadata={key: coerced_value, "created_from": "haystack"}
        )
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

    def update_llm_metadata(self, component_type: str) -> None:
        """Update metadata for LLM generator components.

        This method updates LLM-specific metadata like usage and model
        information based on the component type and span data.

        Args:
            component_type: The Haystack component type (e.g., "OpenAIChatGenerator")
        """
        try:
            if component_type in constants.SUPPORTED_GENERATORS:
                # Regular generators
                meta = self._data.get(constants.COMPONENT_OUTPUT_KEY, {}).get("meta")
                if meta and len(meta) > 0:
                    m = meta[0]
                    self._span_or_trace_data.update(
                        usage=m.get("usage"), model=m.get("model")
                    )
            elif component_type in constants.SUPPORTED_CHAT_GENERATORS:
                # Chat generators
                replies = self._data.get(constants.COMPONENT_OUTPUT_KEY, {}).get(
                    "replies"
                )
                if replies and len(replies) > 0:
                    meta = replies[0].meta
                    self._span_or_trace_data.update(
                        usage=meta.get("usage"), model=meta.get("model")
                    )
        except (IndexError, AttributeError, KeyError) as e:
            LOGGER.warning(
                "Failed to update LLM metadata for %s: %s", component_type, e
            )

    def update_span_metadata(self, tags: Dict[str, Any]) -> None:
        """Update span metadata based on component type and tags.

        Args:
            tags: Dictionary of tags containing component information
        """
        component_type = tags.get(constants.COMPONENT_TYPE_KEY)

        if component_type and component_type in constants.ALL_SUPPORTED_GENERATORS:
            self.update_llm_metadata(component_type)

        if tags.get(constants.PIPELINE_INPUT_DATA_KEY) is not None:
            input_data = tags.get("haystack.pipeline.input_data", {})
            output_data = tags.get("haystack.pipeline.output_data", {})
            self._span_or_trace_data.update(input=input_data, output=output_data)
