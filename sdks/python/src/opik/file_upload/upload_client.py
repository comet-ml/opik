import dataclasses
from typing import List, Optional, Iterable

import httpx

from ..rest_api import client as rest_api_client
from ..rest_api import types as rest_api_types
from ..rest_api.core import api_error as rest_api_error
from ..rest_client_configurator import retry_decorator
from . import file_upload_monitor
from . import upload_options as file_upload_options

LOCAL_UPLOAD_MAGIC_ID = "BEMinIO"


@dataclasses.dataclass
class MultipartUploadMetadata:
    upload_id: str
    urls: List[str]

    def should_use_s3_uploader(self) -> bool:
        """Allows to check if upload should go directly to S3 or use local backend endpoint."""
        return self.upload_id is not None and self.upload_id != LOCAL_UPLOAD_MAGIC_ID


class RestFileUploadClient:
    """Defines the file upload REST API client wrapper that is used for communication with backend in order
    to start and complete S3 file upload operation as well as multipart upload against local backend.
    Args:
        rest_client: The REST API client to communicate with the backend.
    """

    def __init__(
        self, rest_client: rest_api_client.OpikApi, httpx_client: httpx.Client
    ) -> None:
        self.rest_client = rest_client
        self.httpx_client = httpx_client

    def start_upload(
        self,
        upload_options: file_upload_options.FileUploadOptions,
        num_of_file_parts: int,
        base_url_path: str,
    ) -> MultipartUploadMetadata:
        """Starts upload by sending request to the backend and receiving upload metadata. The upload metadata
        will include the list of pre-signed URLs for direct S3 upload and upload ID assigned to this file
        upload operation. If backend decides to force upload to then local endpoint then list of URLs will
        include only one URL and upload ID will have magic value 'BEMinIO'."""
        response = self.rest_client.attachments.start_multi_part_upload(
            file_name=upload_options.file_name,
            num_of_file_parts=num_of_file_parts,
            entity_type=upload_options.entity_type,
            entity_id=upload_options.entity_id,
            path=base_url_path,
            mime_type=upload_options.mime_type,
            project_name=upload_options.project_name,
        )
        return MultipartUploadMetadata(
            upload_id=response.upload_id, urls=response.pre_sign_urls
        )

    def s3_upload_completed(
        self,
        upload_options: file_upload_options.FileUploadOptions,
        upload_metadata: MultipartUploadMetadata,
        file_parts: List[rest_api_types.MultipartUploadPart],
    ) -> None:
        """Invoked to finalize direct S3 file upload operation on the backend. It is invoked after all file parts
        was successfully uploaded to S3."""
        self.rest_client.attachments.complete_multi_part_upload(
            file_name=upload_options.file_name,
            entity_type=upload_options.entity_type,
            entity_id=upload_options.entity_id,
            file_size=upload_options.file_size,
            upload_id=upload_metadata.upload_id,
            uploaded_file_parts=file_parts,
            project_name=upload_options.project_name,
            mime_type=upload_options.mime_type,
        )

    @retry_decorator.opik_rest_retry
    def upload_file_local(
        self,
        upload_url: str,
        file_path: str,
        monitor: Optional[file_upload_monitor.FileUploadMonitor],
        chunk_size: int = -1,
    ) -> None:
        """Invoked to upload a file to the local backend using httpx client configured with necessary authorization
        headers. Raises the `HTTPStatusError` if one occurred."""
        response = self.httpx_client.put(
            url=upload_url,
            content=_data_generator(file_path, chunk_size=chunk_size, monitor=monitor),
        )
        if response.status_code >= 400:
            # reset monitor to avoid multiplication of sent bytes count on retries
            if monitor is not None:
                monitor.reset()
            # raise rest_api_error.ApiError to trigger retry by our REST retry logic (see: retry_decorator.py)
            raise rest_api_error.ApiError(
                status_code=response.status_code, body=response.content
            )


def _data_generator(
    file_path: str,
    monitor: Optional[file_upload_monitor.FileUploadMonitor],
    chunk_size: int = -1,
) -> Iterable[bytes]:
    with open(file_path, "rb") as file:
        while data := file.read(chunk_size):
            yield data
            if monitor is not None:
                monitor.update(len(data))
