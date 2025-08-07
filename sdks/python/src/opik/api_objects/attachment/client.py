import base64
import logging
import os
from typing import Iterator, List, Literal, Optional

from opik.file_upload import file_uploader, upload_options
from opik.rest_api import client as rest_api_client
from opik.rest_api.types import attachment as rest_api_attachment

LOGGER = logging.getLogger(__name__)

RESTAttachmentDetails = rest_api_attachment.Attachment


class AttachmentClient:
    """
    Client for interacting with attachment-related operations.

    This client provides methods to retrieve attachment lists, download attachments,
    and upload attachments for traces and spans.
    
    The AttachmentClient supports:
    - Listing attachments associated with traces or spans
    - Downloading attachment content as a byte stream
    - Uploading files as attachments to traces or spans
    
    All operations are performed within the context of a specific project and require
    the project name to be provided.
    """

    def __init__(
        self,
        rest_client: rest_api_client.OpikApi,
        url_override: str,
        workspace_name: str,
    ) -> None:
        """
        Initialize the AttachmentClient.

        Args:
            rest_client: The REST API client instance for making backend requests.
            url_override: The base URL for the Opik server.
            workspace_name: The workspace name used for download operations.
            
        Note:
            This client is typically created via ``Opik.get_attachments_client()`` rather
            than being instantiated directly.
        """
        self._rest_client = rest_client
        self._url_override = url_override
        self._workspace_name = workspace_name
        
        # Import httpx here to avoid circular dependencies
        import httpx
        self._httpx_client = httpx.Client()

    def get_attachment_list(
        self,
        project_name: str,
        entity_id: str,
        entity_type: Literal["span", "trace"],
    ) -> List[RESTAttachmentDetails]:  # type: ignore
        """
        Get a list of attachments for a specific entity (trace or span).

        Args:
            project_name: The name of the project containing the entity.
            entity_id: The ID of the trace or span to retrieve attachments for.
            entity_type: The type of entity ("trace" or "span").

        Returns:
            List of attachment detail objects containing metadata about each attachment
            (file name, MIME type, size, etc.).

        Raises:
            ValueError: If the project name cannot be resolved to a project ID.
            ApiError: If the API request fails or returns an error status.
            
        Example:
            ```python
            attachments_client = opik_client.get_attachments_client()
            attachments = attachments_client.get_attachment_list(
                project_name="my-project",
                entity_id="trace-123",
                entity_type="trace"
            )
            for attachment in attachments:
                print(f"File: {attachment.file_name}, Type: {attachment.mime_type}")
            ```
        """
        project_id = self._resolve_project_id(project_name)
        url_override_path = base64.b64encode(self._url_override.encode("utf-8")).decode(
            "utf-8"
        )

        response = self._rest_client.attachments.attachment_list(
            project_id=project_id,
            entity_type=entity_type,
            entity_id=entity_id,
            path=url_override_path,
        )

        return response.content

    def download_attachment(
        self,
        project_name: str,
        entity_type: Literal["trace", "span"],
        entity_id: str,
        file_name: str,
        mime_type: str,
    ) -> Iterator[bytes]:
        """
        Download an attachment as a stream of bytes.

        This method returns an iterator that yields chunks of the file content,
        allowing for efficient handling of large files without loading the entire
        content into memory at once.

        Args:
            project_name: The name of the project containing the entity.
            entity_type: The type of entity ("trace" or "span").
            entity_id: The ID of the trace or span containing the attachment.
            file_name: The name of the file to download.
            mime_type: The MIME type of the file.

        Returns:
            Iterator yielding bytes of the attachment content. Use ``b"".join(iterator)``
            to get the complete file content.

        Raises:
            ValueError: If the project name cannot be resolved to a project ID.
            ApiError: If the API request fails, the file is not found, or access is denied.
            
        Example:
            ```python
            attachments_client = opik_client.get_attachments_client()
            attachment_data = attachments_client.download_attachment(
                project_name="my-project",
                entity_type="trace",
                entity_id="trace-123",
                file_name="data.txt",
                mime_type="text/plain"
            )
            content = b"".join(attachment_data)
            print(content.decode('utf-8'))
            ```
        """
        project_id = self._resolve_project_id(project_name)

        return self._rest_client.attachments.download_attachment(
            container_id=project_id,
            entity_type=entity_type,
            entity_id=entity_id,
            file_name=file_name,
            mime_type=mime_type,
            workspace_name=self._workspace_name,
        )

    def upload_attachment(
        self,
        project_name: str,
        entity_type: Literal["trace", "span"],
        entity_id: str,
        file_path: str,
        file_name: Optional[str] = None,
        mime_type: Optional[str] = None,
    ) -> None:
        """
        Upload an attachment for a specific entity (trace or span).

        This method uploads a file from the local filesystem and associates it with
        the specified trace or span. The upload uses a robust multipart upload system
        that can handle large files efficiently.

        Args:
            project_name: The name of the project containing the entity.
            entity_type: The type of entity ("trace" or "span").
            entity_id: The ID of the trace or span to attach the file to.
            file_path: The path to the file to upload on the local filesystem.
            file_name: The name to assign to the uploaded file. If not provided, 
                      uses the basename of file_path (e.g., "document.pdf" from "/path/to/document.pdf").
            mime_type: The MIME type of the file. If not provided, attempts to automatically
                      detect based on the file extension using the mimetypes module.

        Raises:
            FileNotFoundError: If the specified file_path does not exist.
            ApiError: If the API request fails or the upload is rejected by the server.

        Example:
            ```python
            attachments_client = opik_client.get_attachments_client()
            
            # Upload with explicit file name and MIME type
            attachments_client.upload_attachment(
                project_name="my-project",
                entity_type="trace",
                entity_id="trace-123",
                file_path="/path/to/document.pdf",
                file_name="analysis-report.pdf",
                mime_type="application/pdf"
            )
            
            # Upload with automatic detection
            attachments_client.upload_attachment(
                project_name="my-project",
                entity_type="span",
                entity_id="span-456",
                file_path="/path/to/data.json"
                # file_name and mime_type will be auto-detected
            )
            ```
        """
        if not os.path.exists(file_path):
            raise FileNotFoundError(f"File not found: {file_path}")

        if file_name is None:
            file_name = os.path.basename(file_path)

        if mime_type is None:
            import mimetypes
            mime_type, _ = mimetypes.guess_type(file_path)

        file_size = os.path.getsize(file_path)
        encoded_url_override = base64.b64encode(self._url_override.encode("utf-8")).decode("utf-8")

        upload_opts = upload_options.FileUploadOptions(
            file_path=file_path,
            file_name=file_name,
            file_size=file_size,
            mime_type=mime_type,
            entity_type=entity_type,
            entity_id=entity_id,
            project_name=project_name,
            encoded_url_override=encoded_url_override,
        )

        file_uploader.upload_attachment(
            upload_options=upload_opts,
            rest_client=self._rest_client,
            upload_httpx_client=self._httpx_client,
        )

    def _resolve_project_id(self, project_name: str) -> str:
        project = self._rest_client.projects.retrieve_project(name=project_name)
        return project.id
