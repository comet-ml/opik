import dataclasses
from typing import Optional


@dataclasses.dataclass
class Attachment:
    """Represents an Attachment to be added to the Trace or Span.
    Args:
        data: The data to be added to the Attachment.
        file_name: The custom file name to be used for the data in the Attachment.
    """

    data: str
    file_name: Optional[str] = None
    content_type: Optional[str] = None
