import dataclasses
import os
from typing import Optional

from ..message_processing import messages
from ..types import AttachmentEntityType


@dataclasses.dataclass
class FileUploadOptions:
    file_path: str
    file_name: str
    file_size: int
    mime_type: Optional[str]
    entity_type: AttachmentEntityType
    entity_id: str
    project_name: str
    base_url_path: str  # the encoded base URL


def file_upload_options_from_attachment(
    attachment: messages.CreateAttachmentMessage,
) -> FileUploadOptions:
    file_size = os.path.getsize(attachment.file_path)

    return FileUploadOptions(
        file_path=attachment.file_path,
        file_name=attachment.file_name,
        file_size=file_size,
        mime_type=attachment.mime_type,
        entity_type=attachment.entity_type,
        entity_id=attachment.entity_id,
        project_name=attachment.project_name,
        base_url_path=attachment.url_override_path,
    )
