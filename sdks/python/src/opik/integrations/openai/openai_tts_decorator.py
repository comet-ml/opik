"""Decorator for tracking OpenAI Text-to-Speech (TTS) API calls."""

import logging
from typing import Any, Callable, Dict, Optional, Tuple

from typing_extensions import override

import opik.dict_utils as dict_utils
import opik.llm_usage as llm_usage
from opik.api_objects import span
from opik.decorator import arguments_helpers, base_track_decorator
from opik.types import LLMProvider

LOGGER = logging.getLogger(__name__)

KWARGS_KEYS_TO_LOG_AS_INPUTS = ["input", "voice", "response_format", "speed"]
KWARGS_KEYS_TO_LOG_AS_METADATA = ["model", "voice", "response_format", "speed"]


class OpenaiTTSTrackDecorator(base_track_decorator.BaseTrackDecorator):
    """
    Decorator for tracking OpenAI Text-to-Speech (TTS) API calls.
    
    Tracks calls to `client.audio.speech.create()` and calculates
    character-based usage for cost tracking.
    
    TTS models are priced per character, not per token:
    - tts-1: $0.015 per 1,000 characters
    - tts-1-hd: $0.030 per 1,000 characters
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

        # Split input data and metadata
        input_data, new_metadata = dict_utils.split_dict_by_keys(
            kwargs, keys=KWARGS_KEYS_TO_LOG_AS_INPUTS
        )
        
        # Add TTS-specific metadata
        for key in KWARGS_KEYS_TO_LOG_AS_METADATA:
            if key in kwargs:
                new_metadata[key] = kwargs[key]
        
        metadata = dict_utils.deepmerge(metadata, new_metadata)
        metadata.update(
            {
                "created_from": "openai",
                "type": "openai_tts",
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
        """
        Process TTS output and calculate usage.
        
        The OpenAI TTS API returns audio content directly, not a structured response
        with usage metadata. We calculate character count from the input text
        that was stored in the span's input data.
        """
        # Get the input text from span data to calculate character count
        input_text = ""
        if current_span_data.input and isinstance(current_span_data.input, dict):
            input_text = current_span_data.input.get("input", "")
        
        # Calculate character count
        character_count = len(input_text) if isinstance(input_text, str) else 0
        
        # Create usage object
        opik_usage = None
        if character_count > 0:
            try:
                opik_usage = llm_usage.OpikUsage.from_openai_tts_dict(
                    {"characters": character_count}
                )
            except Exception as e:
                LOGGER.warning(
                    f"Failed to create TTS usage tracking: {e}",
                    exc_info=True
                )

        # For TTS, we don't capture the actual audio output in the span
        # as it would be too large. Instead, we just note that audio was generated.
        output_data = {
            "audio_generated": True,
            "character_count": character_count,
        }

        metadata = {}
        if current_span_data.metadata:
            metadata = current_span_data.metadata.copy()

        result = arguments_helpers.EndSpanParameters(
            output=output_data,
            usage=opik_usage,
            metadata=metadata,
            model=current_span_data.model,
            provider=self.provider,
        )

        return result
