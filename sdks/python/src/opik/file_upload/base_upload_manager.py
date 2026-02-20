import abc
import dataclasses
from typing import Optional

from opik.message_processing import messages

from . import types as upload_types


def message_supports_upload(message: messages.BaseMessage) -> bool:
    """Helper to check if provided message supports upload."""
    return isinstance(message, messages.CreateAttachmentMessage)


@dataclasses.dataclass
class RemainingUploadData:
    uploads: int
    bytes: int
    total_size: int


class BaseFileUploadManager(abc.ABC):
    @abc.abstractmethod
    def upload(
        self,
        message: messages.BaseMessage,
        on_upload_success: Optional[upload_types.OnUploadSuccessCallback],
        on_upload_failed: Optional[upload_types.OnUploadFailureCallback],
    ) -> None:
        pass

    @abc.abstractmethod
    def remaining_data(self) -> RemainingUploadData:
        pass

    @abc.abstractmethod
    def all_done(self) -> bool:
        pass

    @abc.abstractmethod
    def flush(self, timeout: Optional[float], sleep_time: int = 5) -> bool:
        pass

    @abc.abstractmethod
    def close(self) -> None:
        pass
