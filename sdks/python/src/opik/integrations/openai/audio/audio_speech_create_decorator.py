"""
Decorator for OpenAI audio speech creation method (TTS).

Output type: openai._legacy_response.HttpxBinaryResponseContent

This decorator is used for LLM spans that generate audio via TTS.
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

# Input parameters to log for audio speech creation
AUDIO_SPEECH_CREATE_KWARGS_KEYS_TO_LOG_AS_INPUTS = [
    "input",
    "voice",
    "response_format",
    "speed",
    "instructions",
]


class AudioSpeechCreateTrackDecorator(base_track_decorator.BaseTrackDecorator):
    """
    Decorator for tracking OpenAI audio speech creation (TTS) methods (LLM spans).

    Handles: audio.speech.create
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
        assert kwargs is not None, "Expected kwargs to be not None in audio API calls"

        name = track_options.name if track_options.name is not None else func.__name__

        metadata = track_options.metadata if track_options.metadata is not None else {}

        input_data, new_metadata = dict_utils.split_dict_by_keys(
            kwargs, keys=AUDIO_SPEECH_CREATE_KWARGS_KEYS_TO_LOG_AS_INPUTS
        )
        metadata = dict_utils.deepmerge(metadata, new_metadata)
        metadata.update(
            {
                "created_from": "openai",
                "type": "openai_audio",
            }
        )

        tags = ["openai"]
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
        # audio.speech.create returns HttpxBinaryResponseContent (binary audio data),
        # not a Pydantic model. We log the response format info from metadata.
        metadata: Dict[str, Any] = {}

        output_data: Optional[Dict[str, Any]] = None
        if output is not None:
            # Extract useful info from the binary response
            output_data = {
                "content_type": getattr(output.response, "headers", {}).get(
                    "content-type", "audio/mpeg"
                ),
            }

        return arguments_helpers.EndSpanParameters(
            output=output_data,
            usage=None,
            metadata=metadata,
            model=None,
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
