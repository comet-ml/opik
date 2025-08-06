import base64
import logging
from typing import Iterator, List, Literal

from opik.rest_api import client as rest_api_client
from opik.rest_api.types import attachment as rest_api_attachment

LOGGER = logging.getLogger(__name__)

RESTAttachmentDetails = rest_api_attachment.Attachment


class AttachmentClient:
    """
    Client for interacting with attachment-related operations.

    This client provides methods to retrieve attachment lists and download attachments
    for traces and spans.
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
            rest_client: The REST API client instance.
            url_override: The base URL for the Opik server.
            workspace_name: The workspace name for downloads.
        """
        self._rest_client = rest_client
        self._url_override = url_override
        self._workspace_name = workspace_name

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
            entity_id: The ID of the trace or span.
            entity_type: The type of entity ("trace" or "span").

        Returns:
            List of attachment detail REST objects associated with the entity.

        Raises:
            ValueError: If project name cannot be resolved to project ID.
            ApiError: If the API request fails.
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

        Args:
            project_name: The name of the project containing the entity.
            entity_type: The type of entity ("trace" or "span").
            entity_id: The ID of the trace or span.
            file_name: The name of the file to download.
            mime_type: The MIME type of the file.

        Returns:
            Iterator yielding bytes of the attachment content.

        Raises:
            ValueError: If project name cannot be resolved to project ID.
            ApiError: If the API request fails.
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

    def _resolve_project_id(self, project_name: str) -> str:
        project = self._rest_client.projects.retrieve_project(name=project_name)
        return project.id
