import dataclasses

from . import attachment as opik_attachment


@dataclasses.dataclass
class AttachmentWithContext:
    attachment: opik_attachment.Attachment
    entity_type: str
    entity_id: str
    context: str
