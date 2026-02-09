import os
import tempfile
from typing import Generator
from unittest import mock

import pytest

from opik.api_objects import attachment
from opik.api_objects.attachment import converters
from opik.message_processing import messages


@pytest.fixture
def original_file() -> Generator[str, None, None]:
    """Create a file with test content and clean up after test."""
    with tempfile.NamedTemporaryFile(mode="w", suffix=".txt", delete=False) as f:
        f.write("test content")
        path = f.name

    yield path

    if os.path.exists(path):
        os.unlink(path)


@pytest.mark.parametrize(
    "attachment_data,expected",
    [
        (attachment.Attachment(data="test.png", file_name=None), "image/png"),
        (attachment.Attachment(data="test.png", file_name="test.jpg"), "image/jpeg"),
        (
            attachment.Attachment(
                data="test.pdf", file_name=None, content_type="image/jpeg"
            ),
            "image/jpeg",
        ),
    ],
)
def test_guess_attachment_type(attachment_data: attachment.Attachment, expected: str):
    mimetype = converters.guess_attachment_type(attachment_data)
    assert mimetype == expected


def test_attachment_to_message__no_temp_copy(original_file: str):
    url_override = "https://example.com"
    entity_id = "123"
    project_name = "test-project"
    attachment_data = attachment.Attachment(data=original_file, create_temp_copy=False)

    message = converters.attachment_to_message(
        attachment_data=attachment_data,
        entity_type="trace",
        entity_id=entity_id,
        project_name=project_name,
        url_override=url_override,
    )

    assert message == messages.CreateAttachmentMessage(
        file_path=original_file,
        file_name=os.path.basename(original_file),
        mime_type="text/plain",
        entity_type="trace",
        entity_id=entity_id,
        project_name=project_name,
        encoded_url_override="aHR0cHM6Ly9leGFtcGxlLmNvbQ==",
    )


def test_attachment_to_message__file_name(original_file: str):
    url_override = "https://example.com"
    entity_id = "123"
    project_name = "test-project"
    attachment_data = attachment.Attachment(
        data=original_file, file_name="test.jpg", create_temp_copy=False
    )

    message = converters.attachment_to_message(
        attachment_data=attachment_data,
        entity_type="trace",
        entity_id=entity_id,
        project_name=project_name,
        url_override=url_override,
    )

    assert message == messages.CreateAttachmentMessage(
        file_path=original_file,
        file_name="test.jpg",
        mime_type="image/jpeg",
        entity_type="trace",
        entity_id=entity_id,
        project_name=project_name,
        encoded_url_override="aHR0cHM6Ly9leGFtcGxlLmNvbQ==",
    )


def test_attachment_to_message__content_type(original_file: str):
    url_override = "https://example.com"
    entity_id = "123"
    project_name = "test-project"
    attachment_data = attachment.Attachment(
        data=original_file, content_type="image/jpeg", create_temp_copy=False
    )

    message = converters.attachment_to_message(
        attachment_data=attachment_data,
        entity_type="trace",
        entity_id=entity_id,
        project_name=project_name,
        url_override=url_override,
    )

    assert message == messages.CreateAttachmentMessage(
        file_path=original_file,
        file_name=os.path.basename(original_file),
        mime_type="image/jpeg",
        entity_type="trace",
        entity_id=entity_id,
        project_name=project_name,
        encoded_url_override="aHR0cHM6Ly9leGFtcGxlLmNvbQ==",
    )


def test_attachment_to_message__create_temp_copy(original_file: str):
    """Test that create_temp_copy creates a temporary file and sets delete_after_upload."""
    url_override = "https://example.com"
    entity_id = "123"
    project_name = "test-project"

    attachment_data = attachment.Attachment(
        data=original_file,
        file_name="test.txt",
        content_type="text/plain",
        create_temp_copy=True,
    )

    message = converters.attachment_to_message(
        attachment_data=attachment_data,
        entity_type="trace",
        entity_id=entity_id,
        project_name=project_name,
        url_override=url_override,
    )

    # The file_path should be different from the original (it's a temp copy)
    assert message.file_path != original_file
    assert message.file_path.endswith(".txt")
    assert os.path.exists(message.file_path)

    # delete_after_upload should be True when create_temp_copy is used
    assert message.delete_after_upload is True

    # Other fields should be preserved
    assert message.file_name == "test.txt"
    assert message.mime_type == "text/plain"
    assert message.entity_type == "trace"
    assert message.entity_id == entity_id
    assert message.project_name == project_name

    # Verify the temp file has the same content
    with open(message.file_path, "r") as f:
        assert f.read() == "test content"

    # Clean up the temp copy
    os.unlink(message.file_path)


def test_attachment_to_message__create_temp_copy_fails_on_open__uses_original_file_and_does_not_delete(
    original_file: str,
):
    """
    Test that when create_temp_copy is True but opening the source file fails,
    the original file is used and delete_after_upload is False.
    """
    url_override = "https://example.com"
    entity_id = "123"
    project_name = "test-project"

    attachment_data = attachment.Attachment(
        data=original_file,
        file_name="test.txt",
        content_type="text/plain",
        create_temp_copy=True,
    )

    # Patch open to fail when trying to read the source file for copying
    original_open = open

    def mock_open_fail_on_rb(file, mode="r", *args, **kwargs):
        if mode == "rb" and file == original_file:
            raise PermissionError("Cannot read source file")
        return original_open(file, mode, *args, **kwargs)

    with mock.patch("builtins.open", side_effect=mock_open_fail_on_rb):
        message = converters.attachment_to_message(
            attachment_data=attachment_data,
            entity_type="trace",
            entity_id=entity_id,
            project_name=project_name,
            url_override=url_override,
        )

    # The file_path should be the original file (fallback behavior)
    assert message.file_path == original_file

    # delete_after_upload should be False to prevent deleting the original file
    assert message.delete_after_upload is False

    # Other fields should be preserved
    assert message.file_name == "test.txt"
    assert message.mime_type == "text/plain"
    assert message.entity_type == "trace"
    assert message.entity_id == entity_id
    assert message.project_name == project_name

    # Original file should still exist
    assert os.path.exists(original_file)

    # Clean up the temp copy
    os.unlink(message.file_path)


def test_attachment_to_message__create_temp_copy_fails_with_delete_after_upload_true__still_does_not_delete(
    original_file: str,
):
    """
    Test that when create_temp_copy fails and delete_after_upload was explicitly True,
    delete_after_upload is still set to False to protect the original file.
    """
    url_override = "https://example.com"
    entity_id = "123"
    project_name = "test-project"

    attachment_data = attachment.Attachment(
        data=original_file,
        file_name="test.txt",
        content_type="text/plain",
        create_temp_copy=True,
    )

    with mock.patch("shutil.copyfileobj", side_effect=IOError("Disk full")):
        message = converters.attachment_to_message(
            attachment_data=attachment_data,
            entity_type="trace",
            entity_id=entity_id,
            project_name=project_name,
            url_override=url_override,
            delete_after_upload=True,  # Explicitly set to True
        )

    # Even though delete_after_upload was True, it should be False after failure
    assert message.delete_after_upload is False
    assert message.file_path == original_file


def test_attachment_to_message__bytes_data():
    """Test that bytes data is written to a temp file and marked for deletion."""
    url_override = "https://example.com"
    entity_id = "123"
    project_name = "test-project"
    data = b"binary content here"

    attachment_data = attachment.Attachment(
        data=data,
        file_name="test.bin",
        content_type="application/octet-stream",
    )

    message = converters.attachment_to_message(
        attachment_data=attachment_data,
        entity_type="trace",
        entity_id=entity_id,
        project_name=project_name,
        url_override=url_override,
    )

    # A temp file should be created
    assert os.path.exists(message.file_path)
    assert message.file_path != "test.bin"

    # delete_after_upload should be True for bytes data
    assert message.delete_after_upload is True

    # Verify file content matches the bytes
    with open(message.file_path, "rb") as f:
        assert f.read() == data

    # Other fields should be preserved
    assert message.file_name == "test.bin"
    assert message.mime_type == "application/octet-stream"
    assert message.entity_type == "trace"
    assert message.entity_id == entity_id
    assert message.project_name == project_name
    assert message.encoded_url_override == "aHR0cHM6Ly9leGFtcGxlLmNvbQ=="

    # Clean up
    os.unlink(message.file_path)


def test_attachment_to_message__bytes_data_without_file_name():
    """Test bytes data without file_name uses temp file basename."""
    url_override = "https://example.com"
    entity_id = "123"
    project_name = "test-project"
    data = b"some binary data"

    attachment_data = attachment.Attachment(
        data=data,
        content_type="image/png",
    )

    message = converters.attachment_to_message(
        attachment_data=attachment_data,
        entity_type="span",
        entity_id=entity_id,
        project_name=project_name,
        url_override=url_override,
    )

    # file_name should be the basename of the temp file
    assert message.file_name == os.path.basename(message.file_path)
    assert message.delete_after_upload is True
    assert message.mime_type == "image/png"
    assert message.entity_type == "span"

    # Clean up
    os.unlink(message.file_path)


def test_attachment_to_message__bytes_data_infers_mime_type_from_file_name():
    """Test that a mime type is inferred from file_name when data is bytes."""
    url_override = "https://example.com"
    entity_id = "123"
    project_name = "test-project"
    data = b"\x00\x01\x02\x03"

    attachment_data = attachment.Attachment(
        data=data,
        file_name="image.png",
    )

    message = converters.attachment_to_message(
        attachment_data=attachment_data,
        entity_type="trace",
        entity_id=entity_id,
        project_name=project_name,
        url_override=url_override,
    )

    assert message.file_name == "image.png"
    assert message.mime_type == "image/png"
    assert message.delete_after_upload is True

    # Clean up
    os.unlink(message.file_path)


def test_attachment_to_message__bytes_data_default_mime_type():
    """Test that bytes data without file_name or content_type use a binary mime type."""
    url_override = "https://example.com"
    entity_id = "123"
    project_name = "test-project"
    data = b"\x00\x01\x02\x03"

    attachment_data = attachment.Attachment(
        data=data,
    )

    message = converters.attachment_to_message(
        attachment_data=attachment_data,
        entity_type="trace",
        entity_id=entity_id,
        project_name=project_name,
        url_override=url_override,
    )

    # Should use default binary mime type
    assert message.mime_type == "application/octet-stream"
    assert message.delete_after_upload is True

    # Clean up
    os.unlink(message.file_path)


def test_attachment_to_message__bytes_data_write_fails__error_reraised():
    """Test behavior when _write_file_like_to_temp_file fails and error reraised."""
    url_override = "https://example.com"
    entity_id = "123"
    project_name = "test-project"
    data = b"test data"

    attachment_data = attachment.Attachment(
        data=data,
        file_name="test.bin",
        content_type="application/octet-stream",
    )

    with mock.patch(
        "opik.api_objects.attachment.converters._write_file_like_to_temp_file",
        side_effect=OSError("Disk full"),
    ):
        with pytest.raises(OSError):
            converters.attachment_to_message(
                attachment_data=attachment_data,
                entity_type="trace",
                entity_id=entity_id,
                project_name=project_name,
                url_override=url_override,
            )


def test_attachment_to_message__base64_string(request):
    """Test that base64-encoded string is decoded and written to temp file."""
    import base64

    url_override = "https://example.com"
    entity_id = "123"
    project_name = "test-project"
    original_content = b"This is test content for base64 encoding"
    base64_string = base64.b64encode(original_content).decode("utf-8")

    attachment_data = attachment.Attachment(
        data=base64_string,
        file_name="test.txt",
        content_type="text/plain",
    )

    message = converters.attachment_to_message(
        attachment_data=attachment_data,
        entity_type="trace",
        entity_id=entity_id,
        project_name=project_name,
        url_override=url_override,
    )

    request.addfinalizer(
        lambda: os.path.exists(message.file_path) and os.unlink(message.file_path)
    )

    # A temp file should be created
    assert os.path.exists(message.file_path)
    assert message.file_path != base64_string

    # delete_after_upload should be True for base64 data
    assert message.delete_after_upload is True

    # Verify file content matches the decoded bytes
    with open(message.file_path, "rb") as f:
        assert f.read() == original_content

    # Other fields should be preserved
    assert message.file_name == "test.txt"
    assert message.mime_type == "text/plain"
    assert message.entity_type == "trace"
    assert message.entity_id == entity_id
    assert message.project_name == project_name


def test_attachment_to_message__invalid_data_raises_error():
    """Test that invalid attachment data (not bytes, file, or base64) raises ValueError."""
    url_override = "https://example.com"
    entity_id = "123"
    project_name = "test-project"

    invalid_data = "not-a-file-and-not-base64!@#$%"

    attachment_data = attachment.Attachment(
        data=invalid_data,
        file_name="test.txt",
    )

    with pytest.raises(
        ValueError,
        match="Attachment data must be bytes, an existing file path, or a valid base64-encoded string",
    ):
        converters.attachment_to_message(
            attachment_data=attachment_data,
            entity_type="trace",
            entity_id=entity_id,
            project_name=project_name,
            url_override=url_override,
        )
