import os
import tempfile
from typing import Generator

import pytest

from opik.api_objects import attachment
from opik.api_objects.attachment import converters
from opik.message_processing import messages


@pytest.fixture
def original_file() -> Generator[str, None, None]:
    """Create a file with test content and clean up after test."""
    with tempfile.NamedTemporaryFile(
        mode="w", suffix=".txt", delete=False
    ) as f:
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


def test_attachment_to_message__no_temp_copy():
    url_override = "https://example.com"
    entity_id = "123"
    project_name = "test-project"
    attachment_data = attachment.Attachment(data="test.png", create_temp_copy=False)

    message = converters.attachment_to_message(
        attachment_data=attachment_data,
        entity_type="trace",
        entity_id=entity_id,
        project_name=project_name,
        url_override=url_override,
    )

    assert message == messages.CreateAttachmentMessage(
        file_path="test.png",
        file_name="test.png",
        mime_type="image/png",
        entity_type="trace",
        entity_id=entity_id,
        project_name=project_name,
        encoded_url_override="aHR0cHM6Ly9leGFtcGxlLmNvbQ==",
    )


def test_attachment_to_message__file_name():
    url_override = "https://example.com"
    entity_id = "123"
    project_name = "test-project"
    attachment_data = attachment.Attachment(
        data="test.pdf", file_name="test.jpg", create_temp_copy=False
    )

    message = converters.attachment_to_message(
        attachment_data=attachment_data,
        entity_type="trace",
        entity_id=entity_id,
        project_name=project_name,
        url_override=url_override,
    )

    assert message == messages.CreateAttachmentMessage(
        file_path="test.pdf",
        file_name="test.jpg",
        mime_type="image/jpeg",
        entity_type="trace",
        entity_id=entity_id,
        project_name=project_name,
        encoded_url_override="aHR0cHM6Ly9leGFtcGxlLmNvbQ==",
    )


def test_attachment_to_message__content_type():
    url_override = "https://example.com"
    entity_id = "123"
    project_name = "test-project"
    attachment_data = attachment.Attachment(data="test.pdf", content_type="image/jpeg")

    message = converters.attachment_to_message(
        attachment_data=attachment_data,
        entity_type="trace",
        entity_id=entity_id,
        project_name=project_name,
        url_override=url_override,
    )

    assert message == messages.CreateAttachmentMessage(
        file_path="test.pdf",
        file_name="test.pdf",
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
