from typing import Optional, Union

import pydantic


class Attachment(pydantic.BaseModel):
    """
    Represents an Attachment to be added to the Trace or Span.

    Args:
        data: The data to be added to the Attachment. Can be:
            - A file path (str) to an existing file
            - A base64-encoded string (str) representing file content
            - Raw bytes content
        file_name: The custom filename to assign to the data in the attachment.
            If not provided, the original filename of the data will be used.
        content_type: The MIME type of the data to be added to the attachment.
            If not specified, it will be inferred from the data file.
        create_temp_copy: If True, a temporary copy of the file will be created
            before upload. This ensures the file remains available even if the
            original is deleted. The temp file will be deleted after upload.
            Default is True.
    """

    data: Union[str, bytes]
    file_name: Optional[str] = None
    content_type: Optional[str] = None
    create_temp_copy: bool = True
