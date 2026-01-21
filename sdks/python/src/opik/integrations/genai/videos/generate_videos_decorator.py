"""
Decorator for Google GenAI video generation methods (Veo).

Output type: google.genai.types.GenerateVideosOperation

This decorator is used for LLM spans that generate videos.
"""

import copy

from typing import (
    Any,
    Callable,
    Dict,
    List,
    Optional,
    Tuple,
    TYPE_CHECKING,
)
from typing_extensions import override

import opik.dict_utils as dict_utils
from opik.api_objects import span
from opik.decorator import arguments_helpers, base_track_decorator

if TYPE_CHECKING:
    from google.genai.types import GenerateVideosOperation

# Input parameters to log for video generation
VIDEO_GENERATE_KWARGS_KEYS_TO_LOG_AS_INPUTS = [
    "prompt",
    "model",
]

# Response keys to log as output for GenerateVideosOperation
VIDEO_OPERATION_KEYS_TO_LOG_AS_OUTPUT = [
    "name",
    "done",
    "error",
]


class GenerateVideosTrackDecorator(base_track_decorator.BaseTrackDecorator):
    """
    Decorator for tracking Google GenAI video generation methods (LLM spans).

    Handles: models.generate_videos
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
        name = track_options.name if track_options.name is not None else func.__name__

        metadata = (
            copy.copy(track_options.metadata)
            if track_options.metadata is not None
            else {}
        )

        input_data, _ = dict_utils.split_dict_by_keys(
            kwargs, keys=VIDEO_GENERATE_KWARGS_KEYS_TO_LOG_AS_INPUTS
        )

        # Add config to both input and metadata
        config = kwargs.get("config")
        if config is not None:
            if hasattr(config, "model_dump"):
                input_data["config"] = config.model_dump(mode="json", exclude_none=True)
            elif isinstance(config, dict):
                input_data["config"] = {
                    k: v for k, v in config.items() if v is not None
                }
            metadata = dict(metadata)
            metadata["config"] = (
                config.model_dump(mode="json", exclude_none=True)
                if hasattr(config, "model_dump")
                else {k: v for k, v in config.items() if v is not None}
                if isinstance(config, dict)
                else config
            )

        model = input_data.get("model", None)

        return arguments_helpers.StartSpanParameters(
            name=name,
            input=input_data,
            type=track_options.type,
            tags=track_options.tags,
            metadata=metadata,
            project_name=track_options.project_name,
            model=model,
            provider=self.provider,
        )

    @override
    def _end_span_inputs_preprocessor(
        self,
        output: Optional["GenerateVideosOperation"],
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

        result_dict: Dict[str, Any] = output.model_dump(mode="json", exclude_none=True)

        output_data, metadata = dict_utils.split_dict_by_keys(
            result_dict, VIDEO_OPERATION_KEYS_TO_LOG_AS_OUTPUT
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
