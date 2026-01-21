"""
Decorator for google.genai.types.Video.save method.

Tracks video saves with attachments.
"""

import functools
from typing import Any, Callable, Dict, List, Optional, TYPE_CHECKING

import opik
from opik.api_objects import attachment

if TYPE_CHECKING:
    from google.genai.types import GenerateVideosOperation


def patch_videos_save(
    operation: "GenerateVideosOperation",
    project_name: Optional[str],
    tags: Optional[List[str]],
    metadata: Optional[Dict[str, Any]],
    upload_video: bool,
) -> None:
    """Patch save method on all videos in the operation response."""
    if (
        not operation.response
        or not hasattr(operation.response, "generated_videos")
        or not operation.response.generated_videos
    ):
        return

    for generated_video in operation.response.generated_videos:
        video = generated_video.video
        if video is None:
            continue

        # Skip if already patched
        if getattr(video, "_opik_save_patched", False):
            continue

        original_save = video.save
        decorator = _create_video_save_decorator(
            project_name=project_name,
            tags=tags,
            metadata=metadata,
            upload_video=upload_video,
        )
        # Use object.__setattr__ to bypass Pydantic's attribute validation
        object.__setattr__(video, "save", decorator(original_save))
        object.__setattr__(video, "_opik_save_patched", True)


def _create_video_save_decorator(
    project_name: Optional[str],
    tags: Optional[List[str]],
    metadata: Optional[Dict[str, Any]],
    upload_video: bool,
) -> Callable[[Callable[[str], None]], Callable[[str], None]]:
    """Create a decorator that tracks Video.save calls."""

    def decorator(func: Callable[[str], None]) -> Callable[[str], None]:
        @functools.wraps(func)
        def wrapper(file: str) -> Any:
            with opik.start_as_current_span(
                name="video.save",
                input={"file": str(file)},
                metadata=metadata,
                tags=tags,
                type="general",
                project_name=project_name,
            ) as span_data:
                result = func(file)
                if upload_video:
                    span_data.update(
                        attachments=[
                            attachment.Attachment(
                                data=str(file), content_type="video/mp4"
                            )
                        ]
                    )
                return result

        return wrapper

    return decorator
