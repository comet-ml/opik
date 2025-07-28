import logging
from typing import (
    Any,
    Callable,
    Dict,
    Optional,
    Tuple,
)

from openai._response import BinaryAPIResponse
from typing_extensions import override

from opik import dict_utils, llm_usage
from opik.api_objects import span
from opik.decorator import arguments_helpers, base_track_decorator
from opik.types import LLMProvider

LOGGER = logging.getLogger(__name__)

KWARGS_KEYS_TO_LOG_AS_INPUTS = ["input", "model", "voice", "response_format", "speed"]
RESPONSE_KEYS_TO_LOG_AS_OUTPUT = ["format", "voice", "speed"]


class OpenaiAudioSpeechTrackDecorator(base_track_decorator.BaseTrackDecorator):
    """
    An implementation of BaseTrackDecorator designed specifically for tracking
    calls of OpenAI's `audio.speech.create` function.

    Handles character-based usage tracking for TTS models and manages binary
    audio response data appropriately.
    """

    def __init__(self) -> None:
        super().__init__()
        self.provider = "openai"

    @override
    def _start_span_inputs_preprocessor(
        self,
        func: Callable,
        track_options: arguments_helpers.TrackOptions,
        args: Optional[Tuple],
        kwargs: Optional[Dict[str, Any]],
    ) -> arguments_helpers.StartSpanParameters:
        assert kwargs is not None, (
            "Expected kwargs to be not None in audio.speech.create(**kwargs)"
        )

        name = track_options.name if track_options.name is not None else func.__name__

        metadata = track_options.metadata if track_options.metadata is not None else {}

        input, new_metadata = dict_utils.split_dict_by_keys(
            kwargs, keys=KWARGS_KEYS_TO_LOG_AS_INPUTS
        )
        metadata = dict_utils.deepmerge(metadata, new_metadata)
        metadata.update(
            {
                "created_from": "openai",
                "type": "openai_audio_speech",
            }
        )

        tags = ["openai", "tts", "audio"]

        result = arguments_helpers.StartSpanParameters(
            name=name,
            input=input,
            type=track_options.type,
            tags=tags,
            metadata=metadata,
            project_name=track_options.project_name,
            model=kwargs.get("model", None),
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
        # For TTS, we need to handle the binary response and streaming responses
        output_metadata = {}
        opik_usage = None

        # Get original input parameters from span data
        span_input = current_span_data.input or {}
        input_text = span_input.get("input", "")
        voice = span_input.get("voice", "")
        response_format = span_input.get("response_format", "mp3")
        speed = span_input.get("speed", 1.0)

        # Create usage data based on character count
        if input_text:
            usage_dict = {"input_characters": len(input_text)}
            opik_usage = llm_usage.try_build_opik_usage_or_log_error(
                provider=LLMProvider.OPENAI,
                usage=usage_dict,
                logger=LOGGER,
                error_message="Failed to log character usage from openai audio.speech call",
            )

        content_length = None

        if isinstance(output, BinaryAPIResponse):
            # Extract metadata from the response headers
            response_headers = getattr(output, "headers", {})
            content_length = response_headers.get("content-length")
        elif hasattr(output, "response") and hasattr(output.response, "headers"):
            # Handle streaming response case
            response_headers = output.response.headers
            content_length = response_headers.get("content-length")

        # Create output metadata
        output_metadata = {
            "response_format": response_format,
            "voice": voice,
            "speed": speed,
            "audio_size_bytes": int(content_length) if content_length else None,
            "audio_generated": True,
            "is_streaming": hasattr(output, "iter_bytes")
            or hasattr(output, "__iter__"),
        }

        result = arguments_helpers.EndSpanParameters(
            output=output_metadata if capture_output else None,
            usage=opik_usage,
            metadata=output_metadata,
            model=current_span_data.model,
            provider=self.provider,
        )

        return result

    @override
    def _streams_handler(
        self, output: Any, capture_output: bool, generations_aggregator=None
    ):
        # TTS responses are binary, not streaming in the same way as chat completions
        # OpenAI TTS API returns complete audio files, not streaming chunks
        # Return None to indicate this is not a stream that needs special handling
        return None
