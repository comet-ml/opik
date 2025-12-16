from typing import Optional

from opik.file_upload import base_upload_manager

from . import preprocessor
from .. import messages


class FileUploadPreprocessor(preprocessor.MessagePreprocessor):
    """
    Preprocesses messages to handle file uploads.

    This class is responsible for processing messages to determine if they support
    file uploads and delegating the upload task to a file upload manager. It also
    provides functionality to flush pending uploads with configurable timeout and
    sleep intervals.
    """

    def __init__(
        self, file_upload_manager: base_upload_manager.BaseFileUploadManager
    ) -> None:
        self._file_upload_manager = file_upload_manager

    def preprocess(
        self, message: Optional[messages.BaseMessage]
    ) -> Optional[messages.BaseMessage]:
        if message is None:
            # possibly already processed
            return None

        if base_upload_manager.message_supports_upload(message):
            self._file_upload_manager.upload(message)
            return None

        return message

    def flush(self, timeout: Optional[float], sleep_time: int) -> bool:
        return self._file_upload_manager.flush(timeout=timeout, sleep_time=sleep_time)
