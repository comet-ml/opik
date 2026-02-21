"""
Decorator for OpenAI TTS API calls (audio.speech.create).

Output type: openai.types.audio.speech.HttpxBinaryResponseContent

This decorator tracks synchronous TTS API calls, logging:
- Input parameters (model, voice, input text, response_format, speed)
- Usage (input_characters count mapped to prompt_tokens/total_tokens)
- Model and provider metadata
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

# Input parameters to log for TTS creation
TTS_CREATE_KWARGS_KEYS_TO_LOG_AS_INPUTS = [
    "input",
    "voice",
    "response_format",
    "speed",
    "instructions",
]

# Response metadata keys to extract
TTS_RESPONSE_KEYS_TO_LOG_AS_OUTPUT = [
    "content_type",
    "content_length",
]


class TTSCreateTrackDecorator(base_track_decorator.BaseTrackDecorator):
    """
    Decorator for tracking OpenAI TTS API calls (audio.speech.create).

    Handles: audio.speech.create (synchronous, non-streaming)

    The TTS API returns binary audio content (HttpxBinaryResponseContent).
    We track input parameters and character count usage, but do NOT buffer
    the audio content to avoid excessive memory usage.
    """

    def __init__(self, provider: str) -> None:
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

        input_data, new_metadata = dict_utils.split_dict_by_keys(
            kwargs, keys=TTS_CREATE_KWARGS_KEYS_TO_LOG_AS_INPUTS
        )
        metadata = dict_utils.deepmerge(metadata, new_metadata)
        metadata.update(
            {
                "created_from": "openai",
                "type": "openai_tts",
            }
        )

        tags = ["openai", "tts"]
        model = kwargs.get("model", "tts-1")

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
        usage = None
        output_data: Optional[Dict[str, Any]] = None
        metadata: Dict[str, Any] = {}

        # Calculate character-based usage from the input text
        # The input text is stored in the span's input data
        input_text = ""
        if current_span_data.input is not None:
            input_text = current_span_data.input.get("input", "")

        input_characters = len(input_text) if isinstance(input_text, str) else 0

        if input_characters > 0:
            # Map character count to token fields for backend compatibility
            # The backend will use input_characters for audio_speech cost calculation
            usage = {
                "completion_tokens": 0,
                "prompt_tokens": input_characters,
                "total_tokens": input_characters,
                "input_characters": input_characters,
            }
            metadata["input_characters"] = input_characters

        # Extract what we can from the response without consuming the binary content
        if output is not None:
            try:
                # HttpxBinaryResponseContent has a response attribute
                if hasattr(output, "response"):
                    response = output.response
                    content_type = response.headers.get("content-type", "audio/mpeg")
                    content_length = response.headers.get("content-length")
                    output_data = {
                        "content_type": content_type,
                    }
                    if content_length is not None:
                        output_data["content_length"] = int(content_length)
                        metadata["audio_bytes"] = int(content_length)
                else:
                    output_data = {"type": "audio_binary_content"}
            except Exception:
                LOGGER.debug("Failed to extract TTS response metadata", exc_info=True)
                output_data = {"type": "audio_binary_content"}

        return arguments_helpers.EndSpanParameters(
            output=output_data,
            usage=usage,
            metadata=metadata,
            model=current_span_data.model,
            provider=self.provider,
        )

    @override
    def _streams_handler(
        self,
        output: Any,
        capture_output: bool,
        generations_aggregator: Optional[Callable[[List[Any]], Any]],
    ) -> Optional[Any]:
        NOT_A_STREAM = None
        return NOT_A_STREAM
