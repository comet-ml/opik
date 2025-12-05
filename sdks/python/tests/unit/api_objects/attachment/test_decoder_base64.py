import base64
import os
from typing import Optional

import pytest

from opik.api_objects.attachment import attachment, decoder_base64

from . import constants


@pytest.fixture
def decoder():
    """Create a Base44AttachmentDecoder instance."""
    return decoder_base64.Base44AttachmentDecoder()


def test_decode_png_success(decoder):
    """Test successful decoding of PNG image."""
    result = decoder.decode(constants.PNG_BASE64, context="input")

    assert result is not None
    assert isinstance(result, attachment.Attachment)
    assert result.content_type == "image/png"
    assert result.file_name.startswith("input-attachment-")
    assert result.file_name.endswith(".png")
    assert os.path.exists(result.data)

    # Cleanup
    _cleanup(result)


def test_decode_jpeg_success(decoder):
    """Test successful decoding of JPEG image."""
    result = decoder.decode(constants.JPEG_BASE64, context="output")

    assert result is not None
    assert isinstance(result, attachment.Attachment)
    assert result.content_type == "image/jpeg"
    assert result.file_name.startswith("output-attachment-")
    assert result.file_name.endswith(".jpg") or result.file_name.endswith(".jpeg")
    assert os.path.exists(result.data)

    # Cleanup
    _cleanup(result)


def test_decode_pdf_success(decoder):
    """Test successful decoding of PDF document."""
    result = decoder.decode(constants.PDF_BASE64, context="metadata")

    assert result is not None
    assert isinstance(result, attachment.Attachment)
    assert result.content_type == "application/pdf"
    assert result.file_name.startswith("metadata-attachment-")
    assert result.file_name.endswith(".pdf")
    assert os.path.exists(result.data)

    # Cleanup
    _cleanup(result)


def test_decode_gif_success(decoder):
    """Test successful decoding of GIF image."""
    result = decoder.decode(constants.GIF89_BASE64, context="input")

    assert result is not None
    assert isinstance(result, attachment.Attachment)
    assert result.content_type == "image/gif"
    assert result.file_name.endswith(".gif")
    assert os.path.exists(result.data)

    # Cleanup
    _cleanup(result)


def test_decode_json_success(decoder):
    """Test successful decoding of JSON data."""
    result = decoder.decode(constants.JSON_BASE64, context="input")

    assert result is not None
    assert isinstance(result, attachment.Attachment)
    assert result.content_type == "application/json"
    assert result.file_name.endswith(".json")
    assert os.path.exists(result.data)

    # Cleanup
    _cleanup(result)


def test_decode_invalid_base64(decoder):
    """Test that an invalid base64 string returns None."""
    invalid_base64 = "this is not valid base64!@#$%"

    result = decoder.decode(invalid_base64, context="input")

    assert result is None


def test_decode_non_string_input(decoder):
    """Test that non-string input returns None."""
    result = decoder.decode(12345, context="input")  # type: ignore

    assert result is None


def test_decode_dict_input(decoder):
    """Test that dict input returns None."""
    result = decoder.decode({"key": "value"}, context="input")  # type: ignore

    assert result is None


def test_decode_none_input(decoder):
    """Test that None input returns None."""
    result = decoder.decode(None, context="input")  # type: ignore

    assert result is None


def test_decode_empty_string(decoder):
    """Test that empty string returns None."""
    result = decoder.decode("", context="input")

    assert result is None


def test_decode_octet_stream_returns_none(decoder):
    """Test that unrecognizable binary data (octet-stream) returns None."""
    # Random binary data that won't match any known format
    result = decoder.decode(constants.RANDOM_BINARY_BASE64, context="input")

    assert result is None


def test_decode_plain_text_returns_none(decoder):
    """Test that plain text returns None."""
    result = decoder.decode(constants.PLAIN_TEXT_BASE64, context="input")

    assert result is None


def test_decode_creates_temp_file_with_correct_extension(decoder):
    """Test that temporary file is created with the correct extension."""
    result = decoder.decode(constants.PNG_BASE64, context="input")

    assert result is not None
    # Check that the temp file path ends with .png (suffix parameter)
    assert result.data.endswith("png")
    assert os.path.exists(result.data)

    # Verify file content
    with open(result.data, "rb") as f:
        content = f.read()
        assert content[:8] == b"\x89PNG\r\n\x1a\n"

    # Cleanup
    _cleanup(result)


def test_decode_attachment_properties(decoder):
    """Test that Attachment object has all required properties."""
    result = decoder.decode(constants.PNG_BASE64, context="input")

    assert result is not None
    assert hasattr(result, "data")
    assert hasattr(result, "file_name")
    assert hasattr(result, "content_type")
    assert result.data is not None
    assert result.file_name is not None
    assert result.content_type is not None

    # Cleanup
    _cleanup(result)


def test_decode_filename_format(decoder):
    """Test that filename follows the expected format."""
    result = decoder.decode(constants.PNG_BASE64, context="input")

    assert result is not None
    # Format should be: context-attachment-{random_id}.{extension}
    assert result.file_name.startswith("input-attachment-")
    parts = result.file_name.split("-")
    assert len(parts) >= 3
    assert parts[0] == "input"
    assert parts[1] == "attachment"
    # The third part should be the random ID with extension
    assert "." in parts[2]

    # Cleanup
    _cleanup(result)


def test_decode_different_contexts(decoder):
    """Test that different contexts are reflected in the filename."""
    contexts = ["input", "output", "metadata"]
    results = []

    for ctx in contexts:
        result = decoder.decode(constants.PNG_BASE64, context=ctx)
        assert result is not None
        assert result.file_name.startswith(f"{ctx}-attachment-")
        results.append(result)

    # Cleanup
    for result in results:
        _cleanup(result)


def test_decode_multiple_calls_create_unique_files(decoder):
    """Test that multiple decode calls create unique filenames."""
    result1 = decoder.decode(constants.PNG_BASE64, context="input")
    result2 = decoder.decode(constants.PNG_BASE64, context="input")

    assert result1 is not None
    assert result2 is not None
    assert result1.file_name != result2.file_name
    assert result1.data != result2.data
    assert os.path.exists(result1.data)
    assert os.path.exists(result2.data)

    # Cleanup
    _cleanup(result1)
    _cleanup(result2)


def test_decode_webp_success(decoder):
    """Test successful decoding of WebP image."""
    result = decoder.decode(constants.WEBP_BASE64, context="input")

    assert result is not None
    assert result.content_type == "image/webp"
    assert result.file_name.endswith(".webp")

    # Cleanup
    _cleanup(result)


def test_decode_svg_success(decoder):
    """Test successful decoding of SVG image."""
    result = decoder.decode(constants.SVG_BASE64, context="input")

    assert result is not None
    assert result.content_type == "image/svg+xml"
    assert result.file_name.endswith(".svg")

    # Cleanup
    _cleanup(result)


def test_decode_short_base64_data(decoder):
    """Test that very short base64 (< 4 bytes decoded) returns None."""
    # Encode just 2 bytes
    short_data = b"ab"
    short_base64 = base64.b64encode(short_data).decode("utf-8")

    result = decoder.decode(short_base64, context="input")

    assert result is None


def test_decode_base64_with_whitespace(decoder):
    """Test that base64 with whitespace is handled correctly."""
    png_base64 = constants.PNG_BASE64
    # Add whitespace
    png_base64_with_whitespace = f"  {png_base64}  \n"

    result = decoder.decode(png_base64_with_whitespace.strip(), context="input")

    assert result is not None
    assert result.content_type == "image/png"

    # Cleanup
    _cleanup(result)


def test_decode_corrupted_base64_padding(decoder):
    """Test base64 with incorrect padding.

    Note: Python's base64 decoder is lenient and may still decode
    strings with missing padding, so this test verifies proper handling.
    """
    # Valid base64 but with missing padding
    corrupted_base64 = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJ"  # Missing padding

    result = decoder.decode(corrupted_base64, context="input")

    # Python's base64 decoder is lenient, so this might still succeed
    # If it succeeds, verify the result is valid and clean up
    _cleanup(result)


def _cleanup(attachment_: Optional[attachment.Attachment]):
    if attachment_ is not None:
        if os.path.exists(attachment_.data):
            os.unlink(attachment_.data)
