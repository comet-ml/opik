import dataclasses
from typing import List, Optional

from ...message_processing import messages
from ...rest_api import client as rest_api_client
from ...rest_api import types as rest_api_types


@dataclasses.dataclass
class MultipartUploadMetadata:
    upload_id: str
    pre_sign_urls: Optional[List[str]]

    def should_use_s3_uploader(self) -> bool:
        """Allows to check if upload should go directly to S3 or use local backend endpoint."""
        return self.upload_id is not None and self.upload_id != "BEMinIO"


class UploadRestClient:
    """Defines the file upload REST API client wrapper that is used for communication with backend in order
    to start and complete S3 file upload operation as well as multipart upload against local backend.
    Args:
        rest_client: The REST API client to communicate with the backend.
    """

    def __init__(self, rest_client: rest_api_client.OpikApi):
        self.rest_client = rest_client

    def start_upload(
        self, message: messages.CreateAttachmentMessage, num_of_file_parts: int
    ) -> MultipartUploadMetadata:
        response = self.rest_client.attachments.start_multi_part_upload(
            file_name=message.file_name,
            num_of_file_parts=num_of_file_parts,
            entity_type=message.entity_type,
            entity_id=message.entity_id,
            path=message.base_url_path,
            mime_type=message.mime_type,
            project_name=message.project_name,
        )
        return MultipartUploadMetadata(
            upload_id=response.upload_id, pre_sign_urls=response.pre_sign_urls
        )

    def complete_upload(
        self,
        file_size: int,
        message: messages.CreateAttachmentMessage,
        upload_metadata: MultipartUploadMetadata,
        file_parts: List[rest_api_types.MultipartUploadPart],
    ) -> None:
        self.rest_client.attachments.complete_multi_part_upload(
            file_name=message.file_name,
            entity_type=message.entity_type,
            entity_id=message.entity_id,
            file_size=file_size,
            upload_id=upload_metadata.upload_id,
            uploaded_file_parts=file_parts,
            project_name=message.project_name,
            mime_type=message.mime_type,
        )

    def upload_attachment_local(
        self, message: messages.CreateAttachmentMessage
    ) -> None:
        self.rest_client.attachments.upload_attachment(
            file_name=message.file_name,
            entity_type=message.entity_type,
            entity_id=message.entity_id,
            project_name=message.project_name,
            mime_type=message.mime_type,
        )
