"""
Decorator for OpenAI TTS streaming response API calls
(audio.speech.with_streaming_response.create).

The with_streaming_response variant returns an httpx streaming response
that users iterate over for audio chunks. This decorator wraps the call
to track metadata without buffering the audio or adding latency.

The key difference from the sync decorator:
- with_streaming_response.create() returns a context manager
- Inside the context manager, the response is an HttpxBinaryResponseContent
- We track the same input parameters and character usage
- We do NOT buffer the audio stream
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

# Same input keys as the sync decorator
TTS_CREATE_KWARGS_KEYS_TO_LOG_AS_INPUTS = [
    "input",
    "voice",
    "response_format",
    "speed",
    "instructions",
]


class TTSStreamingResponseCreateTrackDecorator(base_track_decorator.BaseTrackDecorator):
    """
    Decorator for tracking OpenAI TTS streaming response API calls.

    Handles: audio.speech.with_streaming_response.create

    The streaming response API returns a context manager. We decorate
    the .create method on the with_streaming_response object.
    The actual response inside the context manager is an
    HttpxBinaryResponseContent (same as sync create).

    Since with_streaming_response.create returns a context manager (not a
    generator/stream), we treat the output the same as the sync case and
    let the BaseTrackDecorator handle it as a regular function call.
    The span is created and ended around the .create() call.
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
            "Expected kwargs to be not None in "
            "audio.speech.with_streaming_response.create(**kwargs)"
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
                "streaming": True,
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
        metadata: Dict[str, Any] = {"streaming": True}

        # Calculate character-based usage from the input text
        input_text = ""
        if current_span_data.input is not None:
            input_text = current_span_data.input.get("input", "")

        input_characters = len(input_text) if isinstance(input_text, str) else 0

        if input_characters > 0:
            usage = {
                "completion_tokens": 0,
                "prompt_tokens": input_characters,
                "total_tokens": input_characters,
                "input_characters": input_characters,
            }
            metadata["input_characters"] = input_characters

        # The output is a context manager (APIResponse).
        # We extract what we can from HTTP headers without consuming the stream.
        if output is not None:
            try:
                # The streaming response object wraps an httpx.Response
                if hasattr(output, "headers"):
                    content_type = output.headers.get("content-type", "audio/mpeg")
                    content_length = output.headers.get("content-length")
                    output_data = {
                        "content_type": content_type,
                    }
                    if content_length is not None:
                        output_data["content_length"] = int(content_length)
                        metadata["audio_bytes"] = int(content_length)
                elif hasattr(output, "http_response") and hasattr(
                    output.http_response, "headers"
                ):
                    headers = output.http_response.headers
                    content_type = headers.get("content-type", "audio/mpeg")
                    output_data = {"content_type": content_type}
                else:
                    output_data = {"type": "streaming_audio_response"}
            except Exception:
                LOGGER.debug(
                    "Failed to extract TTS streaming response metadata",
                    exc_info=True,
                )
                output_data = {"type": "streaming_audio_response"}

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
        # The streaming response is a context manager, not a generator/stream.
        # BaseTrackDecorator handles it as a normal return value.
        NOT_A_STREAM = None
        return NOT_A_STREAM
