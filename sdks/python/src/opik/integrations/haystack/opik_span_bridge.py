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
        Set a single tag for this span.

        This method is required by the Haystack Span interface.

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

        This method is required by the Haystack Span interface.
        Content tags are used for input/output data that should only be
        logged when content tracing is enabled.

        Args:
            key: The content tag key (e.g., "component.input", "component.output").
            value: The content tag value.
        """
        if not tracing.tracer.is_content_tracing_enabled:
            return

        if key.endswith(".input"):
            self._convert_and_store_input_data(value)
        elif key.endswith(".output"):
            self._convert_and_store_output_data(value)

        self._data[key] = value

    def _convert_and_store_input_data(self, value: Any) -> None:
        """
        Convert Haystack input data to Opik format and store in span.

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
            LOGGER.error(
                "Failed to process input tag (type: %s, value: %r): %s",
                type(value).__name__,
                value,
                e,
                exc_info=True,
            )
            raise exceptions.OpikException(
                f"Failed to process input tag (type: {type(value).__name__}, value: {value!r}): {e}"
            ) from e

    def _convert_and_store_output_data(self, value: Any) -> None:
        """
        Convert Haystack output data to Opik format and store in span.

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
            LOGGER.error(
                "Failed to process output tag. Value: %r, Exception: %s",
                value,
                e,
                exc_info=True,
            )
            # Try to include which key was being processed
            key = (
                "replies"
                if isinstance(value, dict) and "replies" in value
                else "output"
            )
            raise exceptions.OpikException(
                f"Failed to process output tag for key '{key}' with value {value!r}: {e}"
            ) from e

    def get_opik_span_or_trace_data(
        self,
    ) -> Union[opik_span.SpanData, opik_trace.TraceData]:
        """
        Get the underlying Opik span or trace data instance.

        This method provides access to the internal Opik data for integration
        with the OpikTracer class.

        Returns:
            The Opik SpanData or TraceData instance managed by this bridge.
        """
        return self._span_or_trace_data

    def get_correlation_data_for_logs(self) -> Dict[str, Any]:
        """
        Get correlation data for logging purposes.

        This method is required by the Haystack Span interface.

        Returns:
            Empty dictionary as correlation data is not currently supported.
        """
        return {}

    def set_tags(self, tags: Dict[str, Any]) -> None:
        """
        Set multiple tags on this span.

        This is a convenience method that calls set_tag for each key-value pair.

        Args:
            tags: Dictionary of tag keys and values.
        """
        for key, value in tags.items():
            self.set_tag(key, value)

    def _extract_provider_from_component_type(self, component_type: str) -> str:
        """Extract provider name from Haystack component type.

        Args:
            component_type: The Haystack component type (e.g., "OpenAIChatGenerator")

        Returns:
            The provider name (e.g., "openai", "anthropic", "azure")
        """
        # Map component types to provider names
        if "OpenAI" in component_type:
            return "openai"
        elif "Azure" in component_type:
            return "azure"
        elif "Anthropic" in component_type:
            return "anthropic"
        elif "HuggingFace" in component_type:
            return "huggingface"
        elif "Cohere" in component_type:
            return "cohere"
        else:
            # Extract provider name from component type (fallback)
            # Remove common suffixes and convert to lowercase
            provider = (
                component_type.replace("Generator", "").replace("Chat", "").lower()
            )
            return provider if provider else "unknown"

    def _extract_metadata_from_component_output(
        self, component_type: str
    ) -> Dict[str, Any]:
        """Extract metadata dictionary from component output based on component type.

        Args:
            component_type: The Haystack component type

        Returns:
            Dictionary containing usage and model metadata, or empty dict if not found
        """
        if component_type in constants.SUPPORTED_GENERATORS:
            # Regular generators
            meta = self._data.get(constants.COMPONENT_OUTPUT_KEY, {}).get("meta")
            if meta and len(meta) > 0:
                return meta[0]
        elif component_type in constants.SUPPORTED_CHAT_GENERATORS:
            # Chat generators
            replies = self._data.get(constants.COMPONENT_OUTPUT_KEY, {}).get("replies")
            if replies and len(replies) > 0:
                return replies[0].meta

        return {}

    def _extract_and_set_llm_metadata(self, component_type: str) -> None:
        """Extract and set LLM metadata from component output.

        This method extracts LLM-specific metadata like usage, model, and provider
        information from the component output and updates the span data.

        Args:
            component_type: The Haystack component type (e.g., "OpenAIChatGenerator")
        """
        try:
            provider = self._extract_provider_from_component_type(component_type)
            metadata_dict = self._extract_metadata_from_component_output(component_type)

            if metadata_dict:
                self._span_or_trace_data.update(
                    usage=metadata_dict.get("usage"),
                    model=metadata_dict.get("model"),
                    provider=provider,
                )
        except (IndexError, AttributeError, KeyError) as e:
            LOGGER.warning(
                "Failed to update LLM metadata for %s: %s", component_type, e
            )

    def apply_component_metadata(self) -> None:
        """Apply component-specific metadata to span based on internal tag data.

        This method processes the span's internal tags to extract and apply relevant metadata such as:
        - LLM metadata (usage, model, provider) for generator components
        - Pipeline input/output data for pipeline operations

        The tags are already stored in the span's internal data from previous set_tags() calls.
        """
        # Extract component type from internal data
        component_type = self._data.get(constants.COMPONENT_TYPE_KEY)

        if component_type and component_type in constants.ALL_SUPPORTED_GENERATORS:
            self._extract_and_set_llm_metadata(component_type)

        # Check for pipeline data in internal tags
        if self._data.get(constants.PIPELINE_INPUT_DATA_KEY) is not None:
            input_data = self._data.get("haystack.pipeline.input_data", {})
            output_data = self._data.get("haystack.pipeline.output_data", {})
            self._span_or_trace_data.update(input=input_data, output=output_data)
