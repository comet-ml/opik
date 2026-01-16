"""
Decorator for OpenAI video creation methods (create, remix).

Output type: openai.types.video.Video

This decorator is used only for LLM spans that generate videos.
"""

import logging
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
    from openai.types.video import Video

LOGGER = logging.getLogger(__name__)

# Input parameters to log for video generation
VIDEO_CREATE_KWARGS_KEYS_TO_LOG_AS_INPUTS = [
    "prompt",
    "seconds",
    "size",
]

# Input parameters to log for video remix
VIDEO_REMIX_KWARGS_KEYS_TO_LOG_AS_INPUTS = [
    "video_id",
    "prompt",
]

# Response keys to log as output for Video object
VIDEO_RESPONSE_KEYS_TO_LOG_AS_OUTPUT = [
    "id",
    "status",
    "prompt",
    "seconds",
    "size",
    "progress",
    "error",
]


class VideosCreateTrackDecorator(base_track_decorator.BaseTrackDecorator):
    """
    Decorator for tracking OpenAI video creation methods (LLM spans).

    Handles: videos.create, videos.remix
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
        assert kwargs is not None, "Expected kwargs to be not None in videos API calls"

        name = track_options.name if track_options.name is not None else func.__name__

        metadata = track_options.metadata if track_options.metadata is not None else {}

        # Determine which keys to log based on the method name
        func_name = func.__name__
        if func_name == "remix":
            keys_to_log = VIDEO_REMIX_KWARGS_KEYS_TO_LOG_AS_INPUTS
        else:
            keys_to_log = VIDEO_CREATE_KWARGS_KEYS_TO_LOG_AS_INPUTS

        input_data, new_metadata = dict_utils.split_dict_by_keys(
            kwargs, keys=keys_to_log
        )
        metadata = dict_utils.deepmerge(metadata, new_metadata)
        metadata.update(
            {
                "created_from": "openai",
                "type": "openai_videos",
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
        output: Optional["Video"],
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

        result_dict: Dict[str, Any] = output.model_dump(mode="json")

        output_data, metadata = dict_utils.split_dict_by_keys(
            result_dict, VIDEO_RESPONSE_KEYS_TO_LOG_AS_OUTPUT
        )

        # Add video generation info to metadata for cost calculation
        if result_dict.get("seconds") is not None:
            metadata["video_seconds"] = int(result_dict["seconds"])
        if result_dict.get("size") is not None:
            metadata["video_size"] = result_dict["size"]

        return arguments_helpers.EndSpanParameters(
            output=output_data,
            usage=None,
            metadata=metadata,
            model=result_dict.get("model"),
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
