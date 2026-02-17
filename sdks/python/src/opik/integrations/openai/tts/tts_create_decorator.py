"""
Decorator for OpenAI Text-to-Speech (TTS) creation method.

Output type: openai._legacy_response.HttpxBinaryResponseContent

This decorator is used for LLM spans that generate speech audio from text.
The TTS API (client.audio.speech.create) takes text input and returns binary
audio content. Since the response is binary and not a Pydantic model, output
logging captures the response format and content type rather than the raw bytes.
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

from openai._legacy_response import HttpxBinaryResponseContent
from typing_extensions import override

import opik.dict_utils as dict_utils
from opik.api_objects import span
from opik.decorator import arguments_helpers, base_track_decorator

LOGGER = logging.getLogger(__name__)

# Input parameters to log for TTS generation
TTS_CREATE_KWARGS_KEYS_TO_LOG_AS_INPUTS = [
    "input",
    "voice",
    "response_format",
    "speed",
    "instructions",
]


class TTSCreateTrackDecorator(base_track_decorator.BaseTrackDecorator):
    """
    Decorator for tracking OpenAI audio.speech.create method (LLM spans).

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
        assert kwargs is not None, (
            "Expected kwargs to be not None in audio.speech.create API calls"
        )

        name = track_options.name if track_options.name is not None else func.__name__

        metadata = track_options.metadata if track_options.metadata is not None else {}

        # Remove NOT_GIVEN sentinel values from kwargs before logging
        cleaned_kwargs = _remove_not_given_sentinel_values(kwargs)

        input_data, new_metadata = dict_utils.split_dict_by_keys(
            cleaned_kwargs, keys=TTS_CREATE_KWARGS_KEYS_TO_LOG_AS_INPUTS
        )
        metadata = dict_utils.deepmerge(metadata, new_metadata)
        metadata.update(
            {
                "created_from": "openai",
                "type": "openai_tts",
            }
        )

        tags = ["openai"]
        model = cleaned_kwargs.get("model", None)

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

        output_data: Dict[str, Any] = {}

        if isinstance(output, HttpxBinaryResponseContent):
            # Extract useful info from the binary response headers
            try:
                content_type = output.response.headers.get("content-type")
                if content_type is not None:
                    output_data["content_type"] = content_type
            except Exception:
                pass

        return arguments_helpers.EndSpanParameters(
            output=output_data,
            usage=None,
            metadata={},
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


def _remove_not_given_sentinel_values(dict_: Dict[str, Any]) -> Dict[str, Any]:
    """
    The OpenAI client may pass NOT_GIVEN and Omit sentinel values
    for optional parameters that we don't want to track.
    """
    from openai import _types as _openai_types

    return {
        key: value
        for key, value in dict_.items()
        if value is not _openai_types.NOT_GIVEN
        and not isinstance(value, _openai_types.Omit)
    }
