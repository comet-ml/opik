import pytest

from opik.api_objects import attachment
from opik.api_objects.attachment import converters
from opik.message_processing import messages


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


def test_attachment_to_message():
    url_override = "https://example.com"
    entity_id = "123"
    project_name = "test-project"
    attachment_data = attachment.Attachment(data="test.png")

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
    attachment_data = attachment.Attachment(data="test.pdf", file_name="test.jpg")

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
