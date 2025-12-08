import os
import re
from typing import Optional

import pytest

from opik.api_objects.attachment import (
    attachment,
    attachment_context,
    attachments_extractor,
    decoder_helpers,
)

from . import constants


@pytest.fixture
def extractor():
    """Create an AttachmentsExtractor instance with default min_attachment_size."""
    return attachments_extractor.AttachmentsExtractor(min_attachment_size=20)


@pytest.fixture
def extractor_big_threshold():
    """Create an AttachmentsExtractor with a big threshold for testing size limits."""
    return attachments_extractor.AttachmentsExtractor(min_attachment_size=100)


def test_extract_and_replace_single_attachment(extractor):
    """Test extraction of a single attachment from data."""
    data = {"image": constants.PNG_BASE64}

    result = extractor.extract_and_replace(
        data=data, entity_type="span", entity_id="span-123", context="input"
    )

    # Verify attachments were extracted
    assert len(result) == 1
    assert isinstance(result[0], attachment_context.AttachmentWithContext)
    assert result[0].entity_type == "span"
    assert result[0].entity_id == "span-123"
    assert result[0].context == "input"
    assert result[0].attachment_data.content_type == "image/png"

    # Verify data was sanitized
    assert constants.PNG_BASE64 not in data["image"]
    assert "[" in data["image"] and "]" in data["image"]
    assert data["image"].startswith("[") and data["image"].endswith("]")

    # Cleanup
    _cleanup(result[0].attachment_data)


def test_extract_and_replace_multiple_attachments_single_key(extractor):
    """Test extraction of multiple attachments from a single key."""
    # Combine two different base64 strings with some text
    data = {
        "images": f"First image: {constants.PNG_BASE64}, Second image: {constants.JPEG_BASE64}"
    }

    result = extractor.extract_and_replace(
        data=data, entity_type="trace", entity_id="trace-456", context="output"
    )

    # Verify both attachments were extracted
    assert len(result) == 2
    assert all(isinstance(r, attachment_context.AttachmentWithContext) for r in result)
    assert all(r.entity_type == "trace" for r in result)
    assert all(r.entity_id == "trace-456" for r in result)
    assert all(r.context == "output" for r in result)

    # Check content types
    content_types = [r.attachment_data.content_type for r in result]
    assert "image/png" in content_types
    assert "image/jpeg" in content_types

    # Verify data was sanitized - both base64 strings should be replaced
    assert constants.PNG_BASE64 not in data["images"]
    assert constants.JPEG_BASE64 not in data["images"]

    # check that the attachment placeholder is present and has a correct format
    pattern = re.compile(decoder_helpers.ATTACHMENT_FILE_NAME_PLACEHOLDER_REGEX)
    index = 0
    for m in pattern.finditer(data["images"]):
        assert m is not None
        assert m.group(1) == result[index].attachment_data.file_name
        index += 1

    # Cleanup
    for r in result:
        _cleanup(r.attachment_data)


def test_extract_and_replace_multiple_keys_with_attachments(extractor):
    """Test extraction from multiple keys in the data dictionary."""
    data = {
        "input_image": constants.PNG_BASE64,
        "output_pdf": constants.PDF_BASE64,
        "text": "regular text",
    }

    result = extractor.extract_and_replace(
        data=data, entity_type="span", entity_id="span-789", context="metadata"
    )

    # Verify two attachments were extracted (text should be ignored)
    assert len(result) == 2
    content_types = [r.attachment_data.content_type for r in result]
    assert "image/png" in content_types
    assert "application/pdf" in content_types

    # Verify data was sanitized
    assert constants.PNG_BASE64 not in data["input_image"]
    assert constants.PDF_BASE64 not in data["output_pdf"]
    assert data["text"] == "regular text"  # Text unchanged

    # Cleanup
    for r in result:
        _cleanup(r.attachment_data)


def test_extract_and_replace_non_string_values_ignored(extractor):
    """Test that non-string values are not processed."""
    data = {
        "number": 12345,
        "list": [1, 2, 3],
        "dict": {"nested": "value"},
        "none": None,
        "bool": True,
    }

    result = extractor.extract_and_replace(
        data=data, entity_type="span", entity_id="span-001", context="input"
    )

    # No attachments should be extracted
    assert len(result) == 0

    # Data should be unchanged
    assert data["number"] == 12345
    assert data["list"] == [1, 2, 3]
    assert data["dict"] == {"nested": "value"}
    assert data["none"] is None
    assert data["bool"] is True


def test_extract_and_replace_empty_data(extractor):
    """Test with empty data dictionary."""
    data = {}

    result = extractor.extract_and_replace(
        data=data, entity_type="span", entity_id="span-002", context="input"
    )

    assert len(result) == 0
    assert data == {}


def test_extract_and_replace_invalid_base64(extractor):
    """Test that invalid base64 strings are not extracted."""
    data = {
        "invalid": "this is not valid base64!@#$%",
        "also_invalid": "SGVsbG8gV29ybGQ=!invalid",
    }

    result = extractor.extract_and_replace(
        data=data, entity_type="span", entity_id="span-003", context="input"
    )

    # No attachments should be extracted
    assert len(result) == 0

    # Data should be unchanged
    assert data["invalid"] == "this is not valid base64!@#$%"
    assert data["also_invalid"] == "SGVsbG8gV29ybGQ=!invalid"


def test_extract_and_replace_plain_text_not_extracted(extractor):
    """Test that plain text encoded as base64 is not extracted."""
    data = {"text": constants.PLAIN_TEXT_BASE64}

    result = extractor.extract_and_replace(
        data=data, entity_type="span", entity_id="span-004", context="input"
    )

    # Plain text should not be extracted (decoder returns None)
    assert len(result) == 0


def test_extract_and_replace_below_size_threshold(extractor_big_threshold):
    """Test that strings below min_attachment_size are not processed."""
    # PNG base64 is well below 100 bytes
    data = {"short_value": constants.PNG_BASE64}

    result = extractor_big_threshold.extract_and_replace(
        data=data, entity_type="span", entity_id="span-006", context="input"
    )

    # Attachment should not be extracted
    assert len(result) == 0


def test_extract_and_replace_mixed_valid_invalid(extractor):
    """Test extraction with a mix of valid and invalid attachments."""
    data = {
        "valid_png": constants.PNG_BASE64,
        "invalid": "not base64!",
        "valid_jpeg": constants.JPEG_BASE64,
        "plain_text": constants.PLAIN_TEXT_BASE64,
    }

    result = extractor.extract_and_replace(
        data=data, entity_type="span", entity_id="span-007", context="input"
    )

    # Only valid image attachments should be extracted (plain text excluded by decoder)
    assert len(result) == 2
    content_types = [r.attachment_data.content_type for r in result]
    assert "image/png" in content_types
    assert "image/jpeg" in content_types

    # Valid attachments sanitized, invalid ones unchanged
    assert constants.PNG_BASE64 not in data["valid_png"]
    assert constants.JPEG_BASE64 not in data["valid_jpeg"]
    assert data["invalid"] == "not base64!"
    assert data["plain_text"] == constants.PLAIN_TEXT_BASE64

    # Cleanup
    for r in result:
        _cleanup(r.attachment_data)


def test_extract_and_replace_context_preservation(extractor):
    """Test that context is properly preserved in AttachmentWithContext."""
    contexts = ["input", "output", "metadata"]

    for ctx in contexts:
        data = {"image": constants.PNG_BASE64}

        result = extractor.extract_and_replace(
            data=data, entity_type="trace", entity_id="trace-ctx", context=ctx
        )

        assert len(result) == 1
        assert result[0].context == ctx

        # Cleanup
        _cleanup(result[0].attachment_data)


def test_extract_and_replace_entity_info_preservation(extractor):
    """Test that entity type and ID are properly preserved."""
    entity_types = ["span", "trace"]
    entity_ids = ["id-001", "id-002", "id-003"]

    for entity_type in entity_types:
        for entity_id in entity_ids:
            data = {"image": constants.PNG_BASE64}

            result = extractor.extract_and_replace(
                data=data, entity_type=entity_type, entity_id=entity_id, context="input"
            )

            assert len(result) == 1
            assert result[0].entity_type == entity_type
            assert result[0].entity_id == entity_id

            # Cleanup
            _cleanup(result[0].attachment_data)


def test_extract_and_replace_empty_string(extractor):
    """Test with empty string value."""
    data = {"empty": ""}

    result = extractor.extract_and_replace(
        data=data, entity_type="span", entity_id="span-008", context="input"
    )

    assert len(result) == 0
    assert data["empty"] == ""


def test_extract_and_replace_whitespace_only(extractor):
    """Test with whitespace-only value."""
    data = {"whitespace": "   \n\t  "}

    result = extractor.extract_and_replace(
        data=data, entity_type="span", entity_id="span-009", context="input"
    )

    assert len(result) == 0
    assert data["whitespace"] == "   \n\t  "


def test_extract_and_replace_bytes_data(extractor):
    """Test that bytes data is handled correctly."""
    # The _try_extract_attachments checks for (bytes, str)
    data = {"bytes_value": b"some bytes data"}

    result = extractor.extract_and_replace(
        data=data, entity_type="span", entity_id="span-010", context="input"
    )

    # Bytes should not be processed (pattern.finditer expects string)
    assert len(result) == 0


def test_extract_and_replace_attachment_filename_format(extractor):
    """Test that extracted attachments have a correct filename format."""
    data = {"image": constants.PNG_BASE64}

    result = extractor.extract_and_replace(
        data=data, entity_type="span", entity_id="span-011", context="output"
    )

    assert len(result) == 1
    filename = result[0].attachment_data.file_name
    assert filename.startswith("output-attachment-")
    assert filename.endswith(".png")

    # Cleanup
    _cleanup(result[0].attachment_data)


def test_extract_and_replace_sanitized_data_format(extractor):
    """Test that sanitized data contains the attachment filename."""
    data = {"doc": constants.PDF_BASE64}

    result = extractor.extract_and_replace(
        data=data, entity_type="span", entity_id="span-012", context="metadata"
    )

    assert len(result) == 1
    filename = result[0].attachment_data.file_name
    assert f"[{filename}]" == data["doc"]

    # Cleanup
    _cleanup(result[0].attachment_data)


def test_extract_and_replace_multiple_same_attachment(extractor):
    """Test extraction when the same attachment appears multiple times."""
    # Use the same base64 twice
    data = {"images": f"{constants.PNG_BASE64} and {constants.PNG_BASE64}"}

    result = extractor.extract_and_replace(
        data=data, entity_type="span", entity_id="span-013", context="input"
    )

    # Both instances should be extracted
    assert len(result) == 2
    assert all(r.attachment_data.content_type == "image/png" for r in result)

    # Both instances should be replaced
    assert constants.PNG_BASE64 not in data["images"]
    assert data["images"].count("[") == 2
    assert data["images"].count("]") == 2

    # Cleanup
    for r in result:
        _cleanup(r.attachment_data)


def test_extract_and_replace_gif_attachment(extractor):
    """Test extraction of a GIF attachment."""
    data = {"gif": constants.GIF89_BASE64}

    result = extractor.extract_and_replace(
        data=data, entity_type="span", entity_id="span-014", context="input"
    )

    assert len(result) == 1
    assert result[0].attachment_data.content_type == "image/gif"
    assert result[0].attachment_data.file_name.endswith(".gif")

    # Cleanup
    _cleanup(result[0].attachment_data)


def test_extract_and_replace_webp_attachment(extractor):
    """Test extraction of WebP attachment."""
    data = {"webp": constants.WEBP_BASE64}

    result = extractor.extract_and_replace(
        data=data, entity_type="span", entity_id="span-015", context="input"
    )

    assert len(result) == 1
    assert result[0].attachment_data.content_type == "image/webp"
    assert result[0].attachment_data.file_name.endswith(".webp")

    # Cleanup
    _cleanup(result[0].attachment_data)


def test_extract_and_replace_svg_attachment(extractor):
    """Test extraction of SVG attachment."""
    data = {"svg": constants.SVG_BASE64}

    result = extractor.extract_and_replace(
        data=data, entity_type="span", entity_id="span-016", context="input"
    )

    assert len(result) == 1
    assert result[0].attachment_data.content_type == "image/svg+xml"
    assert result[0].attachment_data.file_name.endswith(".svg")

    # Cleanup
    _cleanup(result[0].attachment_data)


def test_extract_and_replace_json_attachment(extractor):
    """Test extraction of JSON attachment."""
    data = {"json": constants.JSON_BASE64}

    result = extractor.extract_and_replace(
        data=data, entity_type="span", entity_id="span-017", context="input"
    )

    assert len(result) == 1
    assert result[0].attachment_data.content_type == "application/json"
    assert result[0].attachment_data.file_name.endswith(".json")

    # Cleanup
    _cleanup(result[0].attachment_data)


def test_extract_and_replace_octet_stream_not_extracted(extractor):
    """Test that unrecognizable binary data is not extracted."""
    data = {"binary": constants.RANDOM_BINARY_BASE64}

    result = extractor.extract_and_replace(
        data=data, entity_type="span", entity_id="span-018", context="input"
    )

    # Should not be extracted (decoder returns None for octet-stream)
    assert len(result) == 0
    assert data["binary"] == constants.RANDOM_BINARY_BASE64


def test_extract_and_replace_data_mutation(extractor):
    """Test that the original data dictionary is mutated in place."""
    data = {"image": constants.PNG_BASE64}
    original_data_ref = data

    result = extractor.extract_and_replace(
        data=data, entity_type="span", entity_id="span-019", context="input"
    )

    # Verify the same object was mutated
    assert data is original_data_ref
    assert len(result) == 1
    assert constants.PNG_BASE64 not in data["image"]

    # Cleanup
    _cleanup(result[0].attachment_data)


def test_extract_and_replace_complex_text_with_base64(extractor):
    """Test extraction from text containing base64 among other content."""
    data = {
        "message": f"Here is an image: {constants.PNG_BASE64} and some text after",
    }

    result = extractor.extract_and_replace(
        data=data, entity_type="span", entity_id="span-020", context="input"
    )

    assert len(result) == 1
    assert result[0].attachment_data.content_type == "image/png"

    # Verify surrounding text is preserved
    assert "Here is an image:" in data["message"]
    assert "and some text after" in data["message"]
    assert constants.PNG_BASE64 not in data["message"]

    # Verify data was sanitized - base64 string should be replaced
    assert constants.PNG_BASE64 not in data["message"]

    # check that the attachment placeholder is present and has a correct format
    pattern = re.compile(decoder_helpers.ATTACHMENT_FILE_NAME_PLACEHOLDER_REGEX)
    m = pattern.search(data["message"])
    assert m is not None
    assert m.group(1) == result[0].attachment_data.file_name
    assert m.start() == 18

    # Cleanup
    _cleanup(result[0].attachment_data)


def _cleanup(attachment_: Optional[attachment.Attachment]):
    """Helper to cleanup temporary attachment files."""
    if attachment_ is not None:
        if os.path.exists(attachment_.data):
            os.unlink(attachment_.data)
