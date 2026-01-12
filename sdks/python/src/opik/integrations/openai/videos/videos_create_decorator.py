"""
Decorator for OpenAI video creation methods (create, create_and_poll, remix).
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

# Map of video seconds string to integer
VIDEO_SECONDS_MAP = {
    "4": 4,
    "8": 8,
    "12": 12,
}


class VideosCreateTrackDecorator(base_track_decorator.BaseTrackDecorator):
    """
    Decorator for tracking OpenAI video creation methods.

    Handles: videos.create, videos.create_and_poll, videos.remix
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
        output: Any,
        capture_output: bool,
        current_span_data: span.SpanData,
    ) -> arguments_helpers.EndSpanParameters:
        output_data: Optional[Dict[str, Any]] = None
        metadata: Dict[str, Any] = {}
        model: Optional[str] = None

        if output is None:
            output_data = None
        elif hasattr(output, "model_dump"):
            # Video object
            result_dict = output.model_dump(mode="json")

            output_data, metadata = dict_utils.split_dict_by_keys(
                result_dict, VIDEO_RESPONSE_KEYS_TO_LOG_AS_OUTPUT
            )

            model = result_dict.get("model", None)

            # Add video generation usage info to metadata for cost calculation
            seconds_str = result_dict.get("seconds")
            if seconds_str is not None:
                seconds_int = VIDEO_SECONDS_MAP.get(seconds_str)
                if seconds_int is None:
                    try:
                        seconds_int = int(seconds_str)
                    except (ValueError, TypeError):
                        seconds_int = None
                if seconds_int is not None:
                    metadata["video_seconds"] = seconds_int

            size = result_dict.get("size")
            if size is not None:
                metadata["video_size"] = size
        else:
            output_data = {"result_type": type(output).__name__}

        result = arguments_helpers.EndSpanParameters(
            output=output_data,
            usage=None,
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
