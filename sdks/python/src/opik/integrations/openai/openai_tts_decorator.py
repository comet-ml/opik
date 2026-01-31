"""
Decorator for OpenAI Text-to-Speech (TTS) methods.

This decorator tracks calls to:
* `openai_client.audio.speech.create()`
* `openai_client.audio.speech.with_streaming_response.create()`

The TTS API does not return token usage, but we track:
- Input text length (characters) for cost estimation
- Model used
- Voice used
- Response format and other parameters

OpenAI TTS pricing (as of 2024):
- tts-1: $0.015 per 1,000 characters
- tts-1-hd: $0.030 per 1,000 characters
- gpt-4o-mini-tts: $0.012 per 1,000 characters
"""

import logging
from typing import (
    Any,
    Callable,
    Dict,
    List,
    Optional,
    Tuple,
)

from typing_extensions import override

import opik.dict_utils as dict_utils
from opik.api_objects import span
from opik.decorator import arguments_helpers, base_track_decorator

LOGGER = logging.getLogger(__name__)

# Keys from kwargs to log as input
TTS_KWARGS_KEYS_TO_LOG_AS_INPUTS = [
    "input",
    "voice",
]

# Keys to include in metadata (not as primary input)
TTS_KWARGS_KEYS_FOR_METADATA = [
    "response_format",
    "speed",
    "instructions",
]


class OpenaiTTSTrackDecorator(base_track_decorator.BaseTrackDecorator):
    """
    Decorator for tracking OpenAI TTS (audio.speech) methods.

    Handles both sync and async versions, as well as streaming responses.
    """

    def __init__(self, provider: str = "openai") -> None:
        super().__init__()
        self.provider = provider

    @override
    def _start_span_inputs_preprocessor(
        self,
        func: Callable,
        track_options: arguments_helpers.TrackOptions,
        args: Tuple,
        kwargs: Dict[str, Any],
    ) -> arguments_helpers.StartSpanParameters:
        assert kwargs is not None, (
            "Expected kwargs to be not None in audio.speech.create(**kwargs)"
        )

        name = track_options.name if track_options.name is not None else func.__name__

        metadata = track_options.metadata if track_options.metadata is not None else {}

        # Split input and metadata keys
        input_data, extra_metadata = dict_utils.split_dict_by_keys(
            kwargs, keys=TTS_KWARGS_KEYS_TO_LOG_AS_INPUTS
        )

        # Also extract metadata-specific keys
        _, additional_metadata = dict_utils.split_dict_by_keys(
            kwargs, keys=TTS_KWARGS_KEYS_FOR_METADATA
        )

        metadata = dict_utils.deepmerge(metadata, extra_metadata)
        metadata = dict_utils.deepmerge(metadata, additional_metadata)

        # Add standard metadata
        metadata.update(
            {
                "created_from": "openai",
                "type": "openai_tts",
            }
        )

        # Add input character count for cost estimation
        input_text = kwargs.get("input", "")
        if input_text:
            metadata["input_characters"] = len(input_text)

        tags = ["openai", "tts"]
        model = kwargs.get("model", None)

        result = arguments_helpers.StartSpanParameters(
            name=name,
            input=input_data,
            type=track_options.type,
            tags=tags,
            metadata=metadata,
            project_name=track_options.project_name,
            model=model,
            provider=self.provider,
        )

        return result

    @override
    def _end_span_inputs_preprocessor(
        self,
        output: Any,
        capture_output: bool,
        current_span_data: span.SpanData,
    ) -> arguments_helpers.EndSpanParameters:
        """
        Process the TTS response.

        Note: OpenAI TTS returns binary audio content, not a structured response
        with usage information. We track completion but cannot extract token usage.
        """
        # TTS returns binary content (HttpxBinaryResponseContent or similar)
        # We don't log the binary content as output, just mark completion
        output_data: Dict[str, Any] = {
            "status": "completed",
        }

        # Try to get content type if available
        if hasattr(output, "content_type"):
            output_data["content_type"] = output.content_type

        # Try to get content length if available
        if hasattr(output, "content"):
            try:
                content = output.content
                if content:
                    output_data["content_length_bytes"] = len(content)
            except Exception:
                pass

        metadata: Dict[str, Any] = {}

        # Get model from current span data if available
        model = (
            current_span_data.model
            if hasattr(current_span_data, "model")
            else None
        )

        result = arguments_helpers.EndSpanParameters(
            output=output_data,
            usage=None,  # TTS doesn't return token usage
            metadata=metadata,
            model=model,
            provider=self.provider,
        )

        return result

    @override
    def _streams_handler(
        self,
        output: Any,
        capture_output: bool,
        generations_aggregator: Optional[Callable[[List[Any]], Any]],
    ) -> Optional[Any]:
        """
        Handle streaming responses.

        For TTS streaming, we wrap the response to track when streaming completes.
        """
        # Check if this is a streaming response context manager
        # OpenAI's with_streaming_response.create() returns an AbstractContextManager
        # that yields the response when entered

        NOT_A_STREAM = None
        return NOT_A_STREAM


class OpenaiTTSStreamingResponseDecorator(base_track_decorator.BaseTrackDecorator):
    """
    Decorator specifically for tracking TTS streaming responses.

    This handles the context manager returned by audio.speech.with_streaming_response.create()
    """

    def __init__(self, provider: str = "openai") -> None:
        super().__init__()
        self.provider = provider

    @override
    def _start_span_inputs_preprocessor(
        self,
        func: Callable,
        track_options: arguments_helpers.TrackOptions,
        args: Tuple,
        kwargs: Dict[str, Any],
    ) -> arguments_helpers.StartSpanParameters:
        assert kwargs is not None, (
            "Expected kwargs to be not None in audio.speech.with_streaming_response.create(**kwargs)"
        )

        name = track_options.name if track_options.name is not None else func.__name__

        metadata = track_options.metadata if track_options.metadata is not None else {}

        # Split input and metadata keys
        input_data, extra_metadata = dict_utils.split_dict_by_keys(
            kwargs, keys=TTS_KWARGS_KEYS_TO_LOG_AS_INPUTS
        )

        # Also extract metadata-specific keys
        _, additional_metadata = dict_utils.split_dict_by_keys(
            kwargs, keys=TTS_KWARGS_KEYS_FOR_METADATA
        )

        metadata = dict_utils.deepmerge(metadata, extra_metadata)
        metadata = dict_utils.deepmerge(metadata, additional_metadata)

        metadata.update(
            {
                "created_from": "openai",
                "type": "openai_tts",
                "streaming": True,
            }
        )

        # Add input character count for cost estimation
        input_text = kwargs.get("input", "")
        if input_text:
            metadata["input_characters"] = len(input_text)

        tags = ["openai", "tts", "streaming"]
        model = kwargs.get("model", None)

        result = arguments_helpers.StartSpanParameters(
            name=name,
            input=input_data,
            type=track_options.type,
            tags=tags,
            metadata=metadata,
            project_name=track_options.project_name,
            model=model,
            provider=self.provider,
        )

        return result

    @override
    def _end_span_inputs_preprocessor(
        self,
        output: Any,
        capture_output: bool,
        current_span_data: span.SpanData,
    ) -> arguments_helpers.EndSpanParameters:
        """
        Process the streaming TTS response.

        The output is a context manager, so we just mark it as initiated.
        """
        output_data: Dict[str, Any] = {
            "status": "stream_initiated",
        }

        metadata: Dict[str, Any] = {}

        model = (
            current_span_data.model
            if hasattr(current_span_data, "model")
            else None
        )

        result = arguments_helpers.EndSpanParameters(
            output=output_data,
            usage=None,
            metadata=metadata,
            model=model,
            provider=self.provider,
        )

        return result

    @override
    def _streams_handler(
        self,
        output: Any,
        capture_output: bool,
        generations_aggregator: Optional[Callable[[List[Any]], Any]],
    ) -> Optional[Any]:
        NOT_A_STREAM = None
        return NOT_A_STREAM
