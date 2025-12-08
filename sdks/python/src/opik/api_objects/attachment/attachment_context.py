import dataclasses
from typing import Literal

from . import attachment


@dataclasses.dataclass
class AttachmentWithContext:
    attachment_data: attachment.Attachment
    entity_type: Literal["span", "trace"]
    entity_id: str
    project_name: str
    context: str
