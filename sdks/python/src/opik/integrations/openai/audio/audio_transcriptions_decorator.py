"""
Decorator for OpenAI audio transcriptions methods.

Output types:
  - openai.types.audio.Transcription
  - openai.types.audio.TranscriptionVerbose
  - str (when response_format is text/srt/vtt)

This decorator tracks calls to audio.transcriptions.create().
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

TRANSCRIPTION_KWARGS_KEYS_TO_LOG_AS_INPUTS = [
    "language",
    "prompt",
    "response_format",
    "temperature",
    "timestamp_granularities",
]


class AudioTranscriptionsTrackDecorator(base_track_decorator.BaseTrackDecorator):
    """
    Decorator for tracking OpenAI audio.transcriptions.create() calls.

    Tracks: model name, language, prompt, response format, temperature,
    and the transcribed text output.
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
            "Expected kwargs to be not None in audio.transcriptions.create() calls"
        )

        name = track_options.name if track_options.name is not None else func.__name__

        metadata = track_options.metadata if track_options.metadata is not None else {}

        input_data, new_metadata = dict_utils.split_dict_by_keys(
            kwargs, keys=TRANSCRIPTION_KWARGS_KEYS_TO_LOG_AS_INPUTS
        )
        metadata = dict_utils.deepmerge(metadata, new_metadata)

        metadata.update(
            {
                "created_from": "openai",
                "type": "openai_audio_transcription",
            }
        )

        # Try to get file info for metadata
        file_arg = kwargs.get("file")
        if file_arg is not None:
            if isinstance(file_arg, str):
                input_data["file"] = file_arg
            elif hasattr(file_arg, "name"):
                input_data["file"] = getattr(file_arg, "name", "<file>")
            else:
                input_data["file"] = "<binary>"

        tags = ["openai", "audio", "transcription"]
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
                if isinstance(output, str):
                    output_data["text"] = output
                elif hasattr(output, "model_dump"):
                    # Transcription or TranscriptionVerbose
                    result_dict = output.model_dump(mode="json")
                    output_data["text"] = result_dict.get("text", "")
                    # Extract duration if available (TranscriptionVerbose)
                    if "duration" in result_dict:
                        metadata["audio_duration"] = result_dict["duration"]
                    if "language" in result_dict:
                        metadata["detected_language"] = result_dict["language"]
                    if "segments" in result_dict:
                        metadata["segments_count"] = len(result_dict["segments"])
                else:
                    output_data["output"] = str(output)
            except Exception as e:
                LOGGER.debug(
                    "Failed to extract transcription response details: %s", e
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
