"""
Decorator for OpenAI TTS (Text-To-Speech) audio.speech.create method.

Output type: openai._legacy_response.HttpxBinaryResponseContent
         or openai._response.StreamedBinaryAPIResponse (via with_streaming_response)

This decorator is used for LLM spans that generate speech audio.
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

# Input parameters to log for TTS generation
TTS_KWARGS_KEYS_TO_LOG_AS_INPUTS = [
    "input",
    "voice",
    "instructions",
]


def _remove_not_given_sentinel_values(dict_: Dict[str, Any]) -> Dict[str, Any]:
    """Remove OpenAI NOT_GIVEN sentinel values from a dictionary."""
    from openai._types import NOT_GIVEN, Omit

    return {
        key: value
        for key, value in dict_.items()
        if value is not NOT_GIVEN and not isinstance(value, Omit)
    }


class TtsCreateTrackDecorator(base_track_decorator.BaseTrackDecorator):
    """
    Decorator for tracking OpenAI TTS audio.speech.create method (LLM spans).

    Handles: audio.speech.create, audio.speech.with_streaming_response.create
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

        kwargs = _remove_not_given_sentinel_values(kwargs)

        name = track_options.name if track_options.name is not None else func.__name__

        metadata = track_options.metadata if track_options.metadata is not None else {}

        input_data, new_metadata = dict_utils.split_dict_by_keys(
            kwargs, keys=TTS_KWARGS_KEYS_TO_LOG_AS_INPUTS
        )
        metadata = dict_utils.deepmerge(metadata, new_metadata)
        metadata.update(
            {
                "created_from": "openai",
                "type": "openai_tts",
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
        if output is None:
            return arguments_helpers.EndSpanParameters(
                output=None,
                usage=None,
                metadata={},
                model=None,
                provider=self.provider,
            )

        # The output is HttpxBinaryResponseContent or StreamedBinaryAPIResponse
        # (binary audio data). We don't log the binary content itself, but log
        # metadata about the response.
        output_data: Dict[str, Any] = {
            "content_type": "audio",
        }

        metadata: Dict[str, Any] = {}

        try:
            from openai._legacy_response import HttpxBinaryResponseContent

            if isinstance(output, HttpxBinaryResponseContent):
                response = output.response
                if hasattr(response, "headers"):
                    content_type = response.headers.get("content-type")
                    if content_type:
                        output_data["content_type"] = content_type
                    content_length = response.headers.get("content-length")
                    if content_length:
                        metadata["audio_content_length"] = int(content_length)
        except Exception:
            LOGGER.debug(
                "Failed to extract response metadata from TTS output",
                exc_info=True,
            )

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
