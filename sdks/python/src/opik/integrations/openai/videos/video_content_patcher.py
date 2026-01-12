"""
Patcher for OpenAI video download content to attach videos to spans.
"""

import functools
import logging
import os
import shutil
import tempfile

from openai._legacy_response import HttpxBinaryResponseContent

from opik import opik_context
from opik.api_objects import attachment

LOGGER = logging.getLogger(__name__)

# Save reference to original method at module load time
_original_write_to_file = HttpxBinaryResponseContent.write_to_file


def patch_write_to_file() -> None:
    """
    Patch HttpxBinaryResponseContent.write_to_file to attach
    the video to the current span after writing.

    Creates a temporary copy of the video file for attachment to ensure
    the attachment upload is not affected if the user deletes or modifies
    the original file.
    """

    @functools.wraps(_original_write_to_file)
    def patched_write_to_file(self: HttpxBinaryResponseContent, path: str) -> None:
        # Call the original method
        _original_write_to_file(self, path)

        # Create a temporary copy for attachment
        try:
            temp_dir = tempfile.mkdtemp(prefix="opik_video_")
            temp_path = os.path.join(temp_dir, os.path.basename(path))

            shutil.copy2(path, temp_path)

            video_attachment = attachment.Attachment(
                data=temp_path,
                file_name=os.path.basename(path),
                content_type="video/mp4",
            )
            opik_context.update_current_span(attachments=[video_attachment])

            LOGGER.debug(
                "Video attachment created from temporary copy: %s",
                temp_path,
            )
        except Exception as e:
            LOGGER.debug(
                "Failed to attach video to span: %s",
                str(e),
            )

    HttpxBinaryResponseContent.write_to_file = patched_write_to_file  # type: ignore[method-assign]
