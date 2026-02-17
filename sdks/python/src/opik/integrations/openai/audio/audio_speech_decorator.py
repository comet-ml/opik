"""
Decorator for OpenAI audio speech (TTS) methods.

Output type: openai._legacy_response.HttpxBinaryResponseContent

This decorator tracks calls to audio.speech.create().
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

SPEECH_KWARGS_KEYS_TO_LOG_AS_INPUTS = [
    "input",
    "voice",
    "response_format",
    "speed",
]

SPEECH_KWARGS_KEYS_FOR_METADATA = [
    "instructions",
]


class AudioSpeechTrackDecorator(base_track_decorator.BaseTrackDecorator):
    """
    Decorator for tracking OpenAI audio.speech.create() calls.

    Tracks: model name, input text, voice, response format, speed,
    input text length, and output audio size.
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
            "Expected kwargs to be not None in audio.speech.create() calls"
        )

        name = track_options.name if track_options.name is not None else func.__name__

        metadata = track_options.metadata if track_options.metadata is not None else {}

        input_data, new_metadata = dict_utils.split_dict_by_keys(
            kwargs, keys=SPEECH_KWARGS_KEYS_TO_LOG_AS_INPUTS
        )
        metadata = dict_utils.deepmerge(metadata, new_metadata)

        # Add extra metadata keys
        extra_meta, _ = dict_utils.split_dict_by_keys(
            kwargs, keys=SPEECH_KWARGS_KEYS_FOR_METADATA
        )
        metadata = dict_utils.deepmerge(metadata, extra_meta)

        metadata.update(
            {
                "created_from": "openai",
                "type": "openai_audio_speech",
            }
        )

        # Track input text length for cost estimation
        input_text = kwargs.get("input", "")
        if isinstance(input_text, str):
            metadata["input_text_length"] = len(input_text)

        tags = ["openai", "audio", "tts"]
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
        metadata: Dict[str, Any] = {}
        output_data: Dict[str, Any] = {}

        if output is not None:
            try:
                # HttpxBinaryResponseContent - get response info
                response = output.response
                output_data["status_code"] = response.status_code
                output_data["content_type"] = response.headers.get("content-type", "")

                # Track output audio size
                content_length = response.headers.get("content-length")
                if content_length is not None:
                    metadata["output_audio_bytes"] = int(content_length)
                else:
                    # Read content to get size (content is cached by httpx)
                    try:
                        content = output.content
                        metadata["output_audio_bytes"] = len(content)
                    except Exception:
                        pass
            except Exception as e:
                LOGGER.debug(
                    "Failed to extract audio speech response details: %s", e
                )

        return arguments_helpers.EndSpanParameters(
            output=output_data,
            usage=None,
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
