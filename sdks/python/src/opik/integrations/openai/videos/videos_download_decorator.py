"""
Decorator for OpenAI video download_content method.
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

from opik.api_objects import span
from opik.decorator import arguments_helpers, base_track_decorator
from . import binary_response_write_to_file_decorator

LOGGER = logging.getLogger(__name__)


class VideosDownloadTrackDecorator(base_track_decorator.BaseTrackDecorator):
    """
    Decorator for tracking OpenAI videos.download_content method.

    Also patches the returned HttpxBinaryResponseContent instance's write_to_file
    method to create a tracked span when the video is actually downloaded.
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

        metadata.update(
            {
                "created_from": "openai",
                "type": "openai_videos",
            }
        )

        tags = ["openai"]

        result = arguments_helpers.StartSpanParameters(
            name=name,
            input=kwargs,
            type=track_options.type,
            tags=tags,
            metadata=metadata,
            project_name=track_options.project_name,
        )

        return result

    @override
    def _end_span_inputs_preprocessor(
        self,
        output: Any,
        capture_output: bool,
        current_span_data: span.SpanData,
    ) -> arguments_helpers.EndSpanParameters:
        # Patch write_to_file on the returned instance
        if output is not None:
            _track_instance_write_to_file(output, current_span_data.project_name)

        result = arguments_helpers.EndSpanParameters(
            output={"output": output} if not isinstance(output, dict) else output,
            usage=None,
            metadata={},
            model=None,
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


def _track_instance_write_to_file(
    instance: HttpxBinaryResponseContent,
    project_name: Optional[str],
) -> None:
    """Patch write_to_file on this specific instance to track the download."""
    decorator = binary_response_write_to_file_decorator.create_write_to_file_decorator(
        project_name=project_name,
    )
    instance.write_to_file = decorator(instance.write_to_file)  # type: ignore[method-assign]
