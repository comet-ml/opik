from typing import Dict, List, Optional

from opik.file_upload import base_upload_manager
from opik.message_processing import messages


class FileUploadManagerEmulator(base_upload_manager.BaseFileUploadManager):
    """
    A file upload manager emulator that stores attachment messages in memory.

    Attachments are stored by entity_id (span_id or trace_id) for easy lookup
    by the test backend emulator.
    """

    def __init__(self) -> None:
        self.current_uploads: List[messages.BaseMessage] = []
        # Store attachments by entity_id for lookup
        self.attachments_by_span: Dict[str, List[messages.CreateAttachmentMessage]] = {}
        self.attachments_by_trace: Dict[
            str, List[messages.CreateAttachmentMessage]
        ] = {}

    def upload(self, message: messages.BaseMessage) -> None:
        self.current_uploads.append(message)

        # Store attachment messages by entity for easy lookup
        if isinstance(message, messages.CreateAttachmentMessage):
            if message.entity_type == "span":
                if message.entity_id not in self.attachments_by_span:
                    self.attachments_by_span[message.entity_id] = []
                self.attachments_by_span[message.entity_id].append(message)
            elif message.entity_type == "trace":
                if message.entity_id not in self.attachments_by_trace:
                    self.attachments_by_trace[message.entity_id] = []
                self.attachments_by_trace[message.entity_id].append(message)

    def remaining_data(self) -> base_upload_manager.RemainingUploadData:
        return base_upload_manager.RemainingUploadData(
            uploads=len(self.current_uploads), bytes=-1, total_size=-1
        )

    def flush(self, timeout: Optional[float], sleep_time: int = 5) -> bool:
        self.current_uploads = []
        return True

    def all_done(self) -> bool:
        return len(self.current_uploads) == 0

    def close(self) -> None:
        self.current_uploads = []
        self.attachments_by_span = {}
        self.attachments_by_trace = {}
