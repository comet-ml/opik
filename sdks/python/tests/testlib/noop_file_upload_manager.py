from typing import List

from opik.file_upload import upload_manager
from opik.message_processing import messages


class NoopFileUploadManager(upload_manager.BaseFileUploadManager):
    def __init__(self) -> None:
        self.uploads: List[messages.BaseMessage] = []

    def upload(self, message: messages.BaseMessage) -> None:
        self.uploads.append(message)

    def remaining_data(self) -> upload_manager.RemainingUploadData:
        if len(self.uploads) > 0:
            self.uploads = self.uploads[:-1]

        return upload_manager.RemainingUploadData(
            uploads=len(self.uploads), bytes=-1, total_size=-1
        )

    def all_done(self) -> bool:
        return len(self.uploads) == 0

    def close(self) -> None:
        self.uploads = []
