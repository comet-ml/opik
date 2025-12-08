import dataclasses
from typing import Literal

from . import attachment


@dataclasses.dataclass
class AttachmentWithContext:
    """
    Represents an attachment along with its associated context.

    This class is used to pair an attachment with additional contextual
    information such as the entity type, entity ID, project name, and
    context description. It is specifically useful when dealing with
    attachments related to entities like spans or traces. The context
    can help provide further insights or classification of the
    attachment's purpose.

    Attributes:
        attachment_data: The actual attachment
            object containing the associated data.
        entity_type: The type of entity the
            attachment is associated with. It must be either "span"
            or "trace".
        entity_id: The unique identifier of the related entity.
        project_name: The name of the project to which the
            attachment and its entity belong.
        context: A brief context description for the attachment,
            explaining its purpose or relevance.
    """

    attachment_data: attachment.Attachment
    entity_type: Literal["span", "trace"]
    entity_id: str
    project_name: str
    context: str
