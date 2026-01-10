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

from opik.types import LLMProvider
import opik.dict_utils as dict_utils
import opik.llm_usage as llm_usage
from opik.api_objects import span
from opik.decorator import arguments_helpers, base_track_decorator

LOGGER = logging.getLogger(__name__)

KWARGS_KEYS_TO_LOG_AS_INPUTS = ["input", "voice", "response_format", "speed"]
RESPONSE_KEYS_TO_LOG_AS_OUTPUT = ["content_type", "audio_format"]


class OpenaiAudioSpeechTrackDecorator(base_track_decorator.BaseTrackDecorator):
    """
    An implementation of BaseTrackDecorator designed specifically for tracking
    calls of OpenAI's `audio.speech.create` function for Text-to-Speech.
    """

    def __init__(self) -> None:
        super().__init__()
        self.provider = "openai"

    @override
    def _start_span_inputs_preprocessor(
        self,
        func: Callable,
        track_options: arguments_helpers.TrackOptions,
        args: Tuple,
        kwargs: Dict[str, Any],
    ) -> arguments_helpers.StartSpanParameters:
        assert (
            kwargs is not None
        ), "Expected kwargs to be not None in audio.speech.create(**kwargs)"

        name = track_options.name if track_options.name is not None else func.__name__

        metadata = track_options.metadata if track_options.metadata is not None else {}

        input_data, new_metadata = dict_utils.split_dict_by_keys(
            kwargs, keys=KWARGS_KEYS_TO_LOG_AS_INPUTS
        )
        metadata = dict_utils.deepmerge(metadata, new_metadata)
        metadata.update(
            {
                "created_from": "openai",
                "type": "openai_audio_speech",
            }
        )

        tags = ["openai", "tts"]

        result = arguments_helpers.StartSpanParameters(
            name=name,
            input=input_data,
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
        output_data: Dict[str, Any] = {}
        metadata: Dict[str, Any] = {}

        if hasattr(output, "content_type"):
            output_data["content_type"] = output.content_type
        if hasattr(output, "response") and hasattr(output.response, "headers"):
            content_type = output.response.headers.get("content-type")
            if content_type:
                output_data["content_type"] = content_type

        opik_usage = None
        if current_span_data.input is not None:
            input_text = current_span_data.input.get("input", "")
            if isinstance(input_text, str):
                character_count = len(input_text)
                usage_dict = {
                    "character_count": character_count,
                    "total_tokens": character_count,
                    "prompt_tokens": character_count,
                    "completion_tokens": 0,
                }
                opik_usage = llm_usage.try_build_opik_usage_or_log_error(
                    provider=LLMProvider.OPENAI,
                    usage=usage_dict,
                    logger=LOGGER,
                    error_message="Failed to log character usage from openai TTS call",
                )

        model = current_span_data.model

        result = arguments_helpers.EndSpanParameters(
            output=output_data if output_data else {"status": "completed"},
            usage=opik_usage,
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
