import dataclasses
from typing import Optional

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
