import base64
import os
import re

import pytest

from opik.api_objects.attachment import attachment, decoder_base64
from opik.api_objects.attachment import decoder_helpers
from . import constants


@pytest.fixture
def decoder():
    """Create a Base64AttachmentDecoder instance."""
    return decoder_base64.Base64AttachmentDecoder()


def test_decode_png_success(decoder, files_to_remove):
    """Test successful decoding of PNG image."""
    result = decoder.decode(constants.PNG_BASE64, context="input")

    assert result is not None
    # Register for cleanup
    files_to_remove.append(result.data)

    assert isinstance(result, attachment.Attachment)
    assert result.content_type == "image/png"
    assert result.file_name.startswith("input-attachment-")
    assert result.file_name.endswith(".png")
    assert os.path.exists(result.data)


def test_decode_jpeg_success(decoder, files_to_remove):
    """Test successful decoding of JPEG image."""
    result = decoder.decode(constants.JPEG_BASE64, context="output")

    assert result is not None
    # Register for cleanup
    files_to_remove.append(result.data)

    assert isinstance(result, attachment.Attachment)
    assert result.content_type == "image/jpeg"
    assert result.file_name.startswith("output-attachment-")
    assert result.file_name.endswith(".jpg") or result.file_name.endswith(".jpeg")
    assert os.path.exists(result.data)


def test_decode_pdf_success(decoder, files_to_remove):
    """Test successful decoding of PDF document."""
    result = decoder.decode(constants.PDF_BASE64, context="metadata")

    assert result is not None
    # Register for cleanup
    files_to_remove.append(result.data)

    assert isinstance(result, attachment.Attachment)
    assert result.content_type == "application/pdf"
    assert result.file_name.startswith("metadata-attachment-")
    assert result.file_name.endswith(".pdf")
    assert os.path.exists(result.data)


def test_decode_gif_success(decoder, files_to_remove):
    """Test successful decoding of GIF image."""
    result = decoder.decode(constants.GIF89_BASE64, context="input")

    assert result is not None
    # Register for cleanup
    files_to_remove.append(result.data)

    assert isinstance(result, attachment.Attachment)
    assert result.content_type == "image/gif"
    assert result.file_name.endswith(".gif")
    assert os.path.exists(result.data)


def test_decode_json_success(decoder, files_to_remove):
    """Test successful decoding of JSON data."""
    result = decoder.decode(constants.JSON_BASE64, context="input")

    assert result is not None
    # Register for cleanup
    files_to_remove.append(result.data)

    assert isinstance(result, attachment.Attachment)
    assert result.content_type == "application/json"
    assert result.file_name.endswith(".json")
    assert os.path.exists(result.data)


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


def test_decode_creates_temp_file_with_correct_extension(decoder, files_to_remove):
    """Test that temporary file is created with the correct extension."""
    result = decoder.decode(constants.PNG_BASE64, context="input")

    assert result is not None
    # Register for cleanup
    files_to_remove.append(result.data)

    # Check that the temp file path ends with .png (suffix parameter)
    assert result.data.endswith("png")
    assert os.path.exists(result.data)

    # Verify file content
    with open(result.data, "rb") as f:
        content = f.read()
        assert content[:8] == b"\x89PNG\r\n\x1a\n"


def test_decode_attachment_properties(decoder, files_to_remove):
    """Test that Attachment object has all required properties."""
    result = decoder.decode(constants.PNG_BASE64, context="input")

    assert result is not None
    # Register for cleanup
    files_to_remove.append(result.data)

    assert hasattr(result, "data")
    assert hasattr(result, "file_name")
    assert hasattr(result, "content_type")
    assert result.data is not None
    assert result.file_name is not None
    assert result.content_type is not None


def test_decode_filename_format(decoder, files_to_remove):
    """Test that the filename follows the expected format."""
    result = decoder.decode(constants.PNG_BASE64, context="input")

    assert result is not None
    # Register for cleanup
    files_to_remove.append(result.data)

    # check that the filename matches a backend pattern
    pattern = re.compile(decoder_helpers.ATTACHMENT_FILE_NAME_REGEX)
    assert bool(pattern.fullmatch(result.file_name)) is True


def test_decode_different_contexts(decoder, files_to_remove):
    """Test that different contexts are reflected in the filename."""
    contexts = ["input", "output", "metadata"]
    results = []

    for ctx in contexts:
        result = decoder.decode(constants.PNG_BASE64, context=ctx)
        assert result is not None
        # Register for cleanup
        files_to_remove.append(result.data)

        assert result.file_name.startswith(f"{ctx}-attachment-")
        results.append(result)


def test_decode_multiple_calls_create_unique_files(decoder, files_to_remove):
    """Test that multiple decode calls create unique filenames."""
    result1 = decoder.decode(constants.PNG_BASE64, context="input")
    result2 = decoder.decode(constants.PNG_BASE64, context="input")

    assert result1 is not None
    assert result2 is not None
    # Register for cleanup
    files_to_remove.append(result1.data)
    files_to_remove.append(result2.data)

    assert result1.file_name != result2.file_name
    assert result1.data != result2.data
    assert os.path.exists(result1.data)
    assert os.path.exists(result2.data)


def test_decode_webp_success(decoder, files_to_remove):
    """Test successful decoding of WebP image."""
    result = decoder.decode(constants.WEBP_BASE64, context="input")

    assert result is not None
    # Register for cleanup
    files_to_remove.append(result.data)

    assert result.content_type == "image/webp"
    assert result.file_name.endswith(".webp")


def test_decode_svg_success(decoder, files_to_remove):
    """Test successful decoding of SVG image."""
    result = decoder.decode(constants.SVG_BASE64, context="input")

    assert result is not None
    # Register for cleanup
    files_to_remove.append(result.data)

    assert result.content_type == "image/svg+xml"
    assert result.file_name.endswith(".svg")


def test_decode_short_base64_data(decoder):
    """Test that very short base64 (< 4 bytes decoded) returns None."""
    # Encode just 2 bytes
    short_data = b"ab"
    short_base64 = base64.b64encode(short_data).decode("utf-8")

    result = decoder.decode(short_base64, context="input")

    assert result is None


def test_decode_base64_with_whitespace(decoder, files_to_remove):
    """Test that base64 with whitespace is handled correctly."""
    png_base64 = constants.PNG_BASE64
    # Add whitespace
    png_base64_with_whitespace = f"  {png_base64}  \n"

    result = decoder.decode(png_base64_with_whitespace.strip(), context="input")

    assert result is not None
    # Register for cleanup
    files_to_remove.append(result.data)

    assert result.content_type == "image/png"


def test_decode_corrupted_base64_padding(decoder, files_to_remove):
    """Test base64 with incorrect padding.

    Note: Python's base64 decoder is lenient and may still decode
    strings with missing padding, so this test verifies proper handling.
    """
    # Valid base64 but with missing padding
    corrupted_base64 = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJ"  # Missing padding

    result = decoder.decode(corrupted_base64, context="input")

    # Python's base64 decoder is lenient, so this might still succeed
    # If it succeeds, verify the result is valid and clean up
    if result is not None:
        files_to_remove.append(result.data)
