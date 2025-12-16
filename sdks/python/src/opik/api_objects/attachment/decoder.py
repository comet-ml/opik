import abc
from typing import Any, Optional

from . import attachment


class AttachmentDecoder(abc.ABC):
    """
    Abstract base class for decoding file attachments.

    This class serves as an interface for decoding raw attachment data into
    an `Attachment` object. Implementing classes should define the specific
    logic to handle various attachment decoding formats.
    """

    @abc.abstractmethod
    def decode(self, raw_data: str, **kwargs: Any) -> Optional[attachment.Attachment]:
        pass
