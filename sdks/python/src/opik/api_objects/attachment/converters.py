import base64
import os
from typing import Literal, Optional

from ...file_upload import mime_type
from ...message_processing import messages
from . import attachment


def attachment_to_message(
    attachment_data: attachment.Attachment,
    entity_type: Literal["trace", "span"],
    entity_id: str,
    project_name: str,
    url_override: str,
) -> messages.CreateAttachmentMessage:
    if attachment_data.data is None:
        raise ValueError("Attachment data cannot be None")

    mimetype = guess_attachment_type(attachment_data)
    base_url_path = base64.b64encode(url_override.encode("utf-8")).decode("utf-8")
    file_path = attachment_data.data
    file_name = attachment_data.file_name
    if file_name is None:
        file_name = os.path.basename(file_path)

    return messages.CreateAttachmentMessage(
        file_path=file_path,
        file_name=file_name,
        mime_type=mimetype,
        entity_type=entity_type,
        entity_id=entity_id,
        project_name=project_name,
        encoded_url_override=base_url_path,
    )


def guess_attachment_type(attachment_data: attachment.Attachment) -> Optional[str]:
    if attachment_data.content_type is not None:
        return attachment_data.content_type

    mimetype = None
    if attachment_data.file_name is not None:
        mimetype = mime_type.guess_mime_type(file=attachment_data.file_name)

    if mimetype is None and isinstance(attachment_data.data, str):
        mimetype = mime_type.guess_mime_type(file=attachment_data.data)

    return mimetype
