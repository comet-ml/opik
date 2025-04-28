from typing import List, Optional

from opik.file_upload import base_upload_manager
from opik.message_processing import messages


class NoopFileUploadManager(base_upload_manager.BaseFileUploadManager):
    def __init__(self) -> None:
        self.uploads: List[messages.BaseMessage] = []

    def upload(self, message: messages.BaseMessage) -> None:
        self.uploads.append(message)

    def remaining_data(self) -> base_upload_manager.RemainingUploadData:
        return base_upload_manager.RemainingUploadData(
            uploads=len(self.uploads), bytes=-1, total_size=-1
        )

    def flush(self, timeout: Optional[float], sleep_time: int = 5) -> bool:
        self.uploads = []
        return True

    def all_done(self) -> bool:
        return len(self.uploads) == 0

    def close(self) -> None:
        self.uploads = []
