"""
Decorator for HttpxBinaryResponseContent.write_to_file method.
"""

import functools
import logging
import os
import shutil
import tempfile
from typing import Callable, Optional, Any

import opik
from opik.api_objects import attachment, span

LOGGER = logging.getLogger(__name__)


def create_write_to_file_decorator(
    project_name: Optional[str] = None,
) -> Callable[[Callable[[str], None]], Callable[[str], None]]:
    """
    Create a decorator that tracks write_to_file calls.

    Uses functools.wraps with opik context manager to avoid limitations
    of BaseTrackDecorator when the wrapped function returns None.
    """

    def decorator(func: Callable[[str], None]) -> Callable[[str], None]:
        @functools.wraps(func)
        def wrapper(file: str) -> Any:
            with opik.start_as_current_span(
                name="videos_write_to_file",
                input={"file": str(file)},
                metadata={"created_from": "openai", "type": "openai_videos"},
                tags=["openai"],
                type="general",
                provider="openai",
                project_name=project_name,
            ) as span_data:
                result = func(file)
                _attach_video_file(file, span_data)

                # The result is None but we still return it in case the return value
                # is changed in the future.
                return result

        return wrapper

    return decorator


def _attach_video_file(file_path: str, span_data: span.SpanData) -> None:
    """Create a temporary copy of the video and attach it to the span."""
    try:
        temp_dir = tempfile.mkdtemp(prefix="opik_video_")
        file_str = str(file_path)
        temp_path = os.path.join(temp_dir, os.path.basename(file_str))

        shutil.copy2(file_str, temp_path)

        video_attachment = attachment.Attachment(
            data=temp_path,
            file_name=os.path.basename(file_str),
            content_type="video/mp4",
        )
        span_data.update(attachments=[video_attachment])

        LOGGER.debug(
            "Video attachment created from temporary copy: %s",
            temp_path,
        )
    except Exception as e:
        LOGGER.error(
            "Failed to attach video to span: %s",
            str(e),
        )
