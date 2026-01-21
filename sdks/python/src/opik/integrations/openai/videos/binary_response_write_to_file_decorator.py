"""
Decorator for HttpxBinaryResponseContent.write_to_file method.
"""

import functools
from typing import Callable, Optional, Any

import opik
from opik.api_objects import attachment


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
                name="videos.write_to_file",
                input={"file": str(file)},
                metadata={"created_from": "openai", "type": "openai_videos"},
                tags=["openai"],
                type="general",
                project_name=project_name,
            ) as span_data:
                result = func(file)
                span_data.update(
                    attachments=[
                        attachment.Attachment(data=str(file), content_type="video/mp4")
                    ]
                )
                return result

        return wrapper

    return decorator
