from typing import Optional

import pydantic


class Attachment(pydantic.BaseModel):
    """
    Represents an Attachment to be added to the Trace or Span.

    Args:
        data: The data to be added to the Attachment as a path to the file.
        file_name: The custom filename to assign to the data in the attachment.
            If not provided, the original filename of the data will be used.
        content_type: The MIME type of the data to be added to the attachment.
            If not specified, it will be inferred from the data file.
    """

    data: str
    file_name: Optional[str] = None
    content_type: Optional[str] = None
