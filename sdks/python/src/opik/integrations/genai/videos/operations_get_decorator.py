"""
Decorator for google.genai.Client.operations.get method.

When the operation returns a completed video, patches the Video.save method
to track video saves with attachments.
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

from opik.api_objects import span
from opik.decorator import arguments_helpers, base_track_decorator
from opik.decorator import inspect_helpers

from . import video_save_decorator

if TYPE_CHECKING:
    from google.genai.types import GenerateVideosOperation

LOGGER = logging.getLogger(__name__)


class OperationsGetTrackDecorator(base_track_decorator.BaseTrackDecorator):
    """
    Decorator for tracking Google GenAI operations.get method.

    When the operation is done and contains videos, patches Video.save
    to track video saves with attachments.
    """

    def __init__(self, project_name: Optional[str], upload_videos: bool) -> None:
        super().__init__()
        self._project_name = project_name
        self._upload_videos = upload_videos

    @override
    def _start_span_inputs_preprocessor(
        self,
        func: Callable,
        track_options: arguments_helpers.TrackOptions,
        args: Tuple,
        kwargs: Dict[str, Any],
    ) -> arguments_helpers.StartSpanParameters:
        name = track_options.name if track_options.name is not None else func.__name__
        input_data = inspect_helpers.extract_inputs(func, args, kwargs)

        return arguments_helpers.StartSpanParameters(
            name=name,
            input=input_data,
            type=track_options.type,
            tags=track_options.tags,
            metadata=track_options.metadata,
            project_name=track_options.project_name,
        )

    @override
    def _end_span_inputs_preprocessor(
        self,
        output: Optional["GenerateVideosOperation"],
        capture_output: bool,
        current_span_data: span.SpanData,
    ) -> arguments_helpers.EndSpanParameters:
        if output is not None and output.done and output.response:
            video_save_decorator.patch_videos_save(
                output,
                project_name=self._project_name,
                tags=current_span_data.tags,
                metadata=current_span_data.metadata,
                upload_video=self._upload_videos,
            )

        return arguments_helpers.EndSpanParameters(output=output)

    @override
    def _streams_handler(
        self,
        output: Any,
        capture_output: bool,
        generations_aggregator: Optional[Callable[[List[Any]], Any]],
    ) -> Optional[Any]:
        NOT_A_STREAM = None
        return NOT_A_STREAM
