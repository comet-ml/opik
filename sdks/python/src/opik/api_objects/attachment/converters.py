import base64
import binascii
import logging
import os
import shutil
import tempfile
from typing import Literal, Optional

from ...file_upload import mime_type
from ...message_processing import messages
from . import attachment

LOGGER = logging.getLogger(__name__)


def attachment_to_message(
    attachment_data: attachment.Attachment,
    entity_type: Literal["trace", "span"],
    entity_id: str,
    project_name: str,
    url_override: str,
    delete_after_upload: bool = False,
) -> messages.CreateAttachmentMessage:
    if attachment_data.data is None:
        raise ValueError("Attachment data cannot be None")

    mimetype = guess_attachment_type(attachment_data)
    base_url_path = base64.b64encode(url_override.encode("utf-8")).decode("utf-8")

    if isinstance(attachment_data.data, bytes):
        file_path = _write_file_like_to_temp_file(attachment_data.data)
        # make sure the temporary file is deleted after upload
        attachment_data.create_temp_copy = False
        delete_after_upload = True
    elif os.path.exists(attachment_data.data):
        file_path = attachment_data.data
    else:
        try:
            decoded_bytes = base64.b64decode(attachment_data.data, validate=True)
            file_path = _write_file_like_to_temp_file(decoded_bytes)
            attachment_data.create_temp_copy = False
            delete_after_upload = True
        except binascii.Error as e:
            raise ValueError(
                "Attachment data must be bytes, an existing file path, or a valid base64-encoded string."
            ) from e

    file_name = attachment_data.file_name
    should_delete_after_upload = delete_after_upload

    if file_name is None:
        file_name = os.path.basename(file_path)

    # Try to create a temporary copy if requested
    if attachment_data.create_temp_copy:
        tmp_file_path = _try_create_temp_copy(file_path)
        if tmp_file_path is not None:
            file_path = tmp_file_path
            should_delete_after_upload = True
        else:
            should_delete_after_upload = False

    return messages.CreateAttachmentMessage(
        file_path=file_path,
        file_name=file_name,
        mime_type=mimetype,
        entity_type=entity_type,
        entity_id=entity_id,
        project_name=project_name,
        encoded_url_override=base_url_path,
        delete_after_upload=should_delete_after_upload,
    )


def _write_file_like_to_temp_file(file_like: bytes) -> str:
    """
    Writes a bytes-like file object to a temporary file on the filesystem.

    This function accepts a file-like object in the form of bytes and writes its
    contents to a temporary file. The temporary file is not deleted automatically
    on program termination. The path to the created temporary file is returned.

    Args:
        file_like: The bytes-like object containing the file content
            to be written to a temporary file.

    Returns:
        The full path to the created temporary file.
    """
    temp_file = tempfile.NamedTemporaryFile(mode="wb", delete=False)
    try:
        temp_file.write(file_like)
        temp_file.flush()
        LOGGER.debug(
            "Created temporary copy of file-like attachment to file: %s",
            temp_file.name,
        )
    except Exception as e:
        LOGGER.error(
            "Failed to write file-like attachment to temp file: %s",
            e,
            exc_info=True,
        )
        raise
    finally:
        temp_file.close()

    return temp_file.name


def _try_create_temp_copy(file_path: str) -> Optional[str]:
    """
    Create a temporary copy of a file.

    This ensures the file remains available for upload even if the user
    deletes the original file. The temp file is created with delete=False
    so it persists until the upload manager processes and deletes it.

    Args:
        file_path: Path to the original file.

    Returns:
        Path to the temporary copy.
    """
    _, extension = os.path.splitext(file_path)
    temp_file = tempfile.NamedTemporaryFile(mode="wb", delete=False, suffix=extension)
    try:
        with open(file_path, "rb") as original_file:
            shutil.copyfileobj(original_file, temp_file)
        temp_file.flush()
        temp_file.close()
        LOGGER.debug(
            "Created temporary copy of attachment: %s -> %s",
            file_path,
            temp_file.name,
        )
        return temp_file.name
    except Exception:
        temp_file.close()
        LOGGER.error(
            "Failed to create temporary copy of attachment: %s. Opik will try to use the original file.",
            file_path,
            exc_info=True,
        )
        return None


def guess_attachment_type(attachment_data: attachment.Attachment) -> Optional[str]:
    if attachment_data.content_type is not None:
        return attachment_data.content_type

    mimetype = None
    if attachment_data.file_name is not None:
        mimetype = mime_type.guess_mime_type(file=attachment_data.file_name)

    if mimetype is not None:
        return mimetype

    if isinstance(attachment_data.data, str):
        mimetype = mime_type.guess_mime_type(file=attachment_data.data)
    elif isinstance(attachment_data.data, bytes):
        mimetype = mime_type.BINARY_MIME_TYPE

    return mimetype
