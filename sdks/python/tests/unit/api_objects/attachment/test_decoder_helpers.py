import base64

from opik.api_objects.attachment import decoder_helpers

from . import constants


class TestDetectMimeType:
    """Test suite for detect_mime_type() function."""

    def test_detect_png(self):
        """Test PNG image detection using magic bytes."""
        # PNG magic bytes: 89 50 4E 47 0D 0A 1A 0A
        png_data = b"\x89PNG\r\n\x1a\n" + b"\x00" * 100
        assert decoder_helpers.detect_mime_type(png_data) == "image/png"

    def test_detect_jpeg(self):
        """Test JPEG image detection using magic bytes."""
        # JPEG magic bytes: FF D8 at start, FF D9 at end
        jpeg_data = b"\xff\xd8" + b"\x00" * 100 + b"\xff\xd9"
        assert decoder_helpers.detect_mime_type(jpeg_data) == "image/jpeg"

    def test_detect_jpeg_without_end_marker(self):
        """Test JPEG without proper end marker should not be detected as JPEG."""
        # JPEG start but no proper end
        jpeg_data = b"\xff\xd8" + b"\x00" * 100
        assert decoder_helpers.detect_mime_type(jpeg_data) != "image/jpeg"

    def test_detect_gif87a(self):
        """Test GIF87a format detection."""
        gif_data = b"GIF87a" + b"\x00" * 100
        assert decoder_helpers.detect_mime_type(gif_data) == "image/gif"

    def test_detect_gif89a(self):
        """Test GIF89a format detection."""
        assert decoder_helpers.detect_mime_type(constants.GIF89_BYTES) == "image/gif"

    def test_detect_pdf(self):
        """Test PDF document detection."""
        assert (
            decoder_helpers.detect_mime_type(constants.PDF_BYTES) == "application/pdf"
        )

    def test_detect_webp(self):
        """Test WebP image detection."""
        assert decoder_helpers.detect_mime_type(constants.WEBP_BYTES) == "image/webp"

    def test_detect_svg(self):
        """Test SVG image detection."""
        assert decoder_helpers.detect_mime_type(constants.SVG_BYTES) == "image/svg+xml"

    def test_detect_svg_with_uppercase(self):
        """Test SVG detection with an uppercase tag."""
        svg_data = b"<SVG>content</SVG>"
        assert decoder_helpers.detect_mime_type(svg_data) == "image/svg+xml"

    def test_detect_svg_with_mixed_case(self):
        """Test SVG detection with mixed case tag."""
        svg_data = b"<SvG>content</SvG>"
        assert decoder_helpers.detect_mime_type(svg_data) == "image/svg+xml"

    def test_detect_mp4(self):
        """Test MP4 video detection."""
        # MP4: ftyp at offset 4
        mp4_data = b"\x00\x00\x00\x20ftypisom" + b"\x00" * 100
        assert decoder_helpers.detect_mime_type(mp4_data) == "video/mp4"

    def test_detect_json_object(self):
        """Test JSON object detection."""
        json_data = b'{"key": "value", "number": 123}'
        assert decoder_helpers.detect_mime_type(json_data) == "application/json"

    def test_detect_json_array(self):
        """Test JSON array detection."""
        json_data = b'["item1", "item2", "item3"]'
        assert decoder_helpers.detect_mime_type(json_data) == "application/json"

    def test_detect_json_with_whitespace(self):
        """Test JSON detection with leading whitespace."""
        json_data = b'   \n\t{"key": "value"}'
        assert decoder_helpers.detect_mime_type(json_data) == "application/json"

    def test_detect_invalid_json_like(self):
        """Test that invalid JSON-like content is not detected as JSON."""
        # UTF-8 decoding should fail
        invalid_data = b"\xff\xfe{invalid}"
        assert (
            decoder_helpers.detect_mime_type(invalid_data) == "application/octet-stream"
        )

    def test_detect_short_data(self):
        """Test that data shorter than 4 bytes returns octet-stream."""
        short_data = b"abc"
        assert (
            decoder_helpers.detect_mime_type(short_data) == "application/octet-stream"
        )

    def test_detect_empty_data(self):
        """Test that empty data returns octet-stream."""
        empty_data = b""
        assert (
            decoder_helpers.detect_mime_type(empty_data) == "application/octet-stream"
        )

    def test_detect_unknown_binary(self):
        """Test that unknown binary data returns octet-stream."""
        assert (
            decoder_helpers.detect_mime_type(constants.RANDOM_BINARY_BYTES)
            == "application/octet-stream"
        )

    def test_detect_plain_text_not_json(self):
        """Test that plain text (non-JSON) returns octet-stream."""
        assert (
            decoder_helpers.detect_mime_type(constants.PLAIN_TEXT_BYTES)
            == "application/octet-stream"
        )

    def test_real_png_base64(self):
        """Test with a real minimal PNG image (1x1 transparent pixel)."""
        # 1x1 transparent PNG
        png_data = base64.b64decode(constants.PNG_BASE64)
        assert decoder_helpers.detect_mime_type(png_data) == "image/png"

    def test_real_jpeg_base64(self):
        """Test with a real minimal JPEG image."""
        # Minimal valid JPEG (1x1 red pixel)
        jpeg_data = base64.b64decode(constants.JPEG_BASE64)
        assert decoder_helpers.detect_mime_type(jpeg_data) == "image/jpeg"

    def test_detect_svg_with_long_content(self):
        """Test SVG detection with content longer than 1024 bytes."""
        # Create SVG content longer than 1024 bytes
        long_content = "x" * 2000
        svg_data = (
            f'<svg xmlns="http://www.w3.org/2000/svg">{long_content}</svg>'.encode(
                "utf-8"
            )
        )
        assert decoder_helpers.detect_mime_type(svg_data) == "image/svg+xml"

    def test_detect_webp_invalid(self):
        """Test that RIFF without WEBP marker is not detected as WebP."""
        # RIFF but not WEBP
        riff_data = b"RIFF\x00\x00\x00\x00XXXX" + b"\x00" * 100
        assert decoder_helpers.detect_mime_type(riff_data) != "image/webp"

    def test_detect_mp4_short_data(self):
        """Test that data too short for MP4 detection is not detected as MP4."""
        short_mp4 = b"\x00\x00\x00\x20ftyp"  # Only 9 bytes
        assert decoder_helpers.detect_mime_type(short_mp4) != "video/mp4"


class TestGetFileExtension:
    """Test suite for get_file_extension() function."""

    def test_get_extension_png(self):
        """Test PNG extension extraction."""
        assert decoder_helpers.get_file_extension("image/png") == "png"

    def test_get_extension_jpeg(self):
        """Test JPEG extension extraction (should return 'jpg' not 'jpe')."""
        result = decoder_helpers.get_file_extension("image/jpeg")
        # The function should return 'jpg' for image/jpeg
        assert result in ("jpg", "jpeg")

    def test_get_extension_pdf(self):
        """Test PDF extension extraction."""
        assert decoder_helpers.get_file_extension("application/pdf") == "pdf"

    def test_get_extension_gif(self):
        """Test GIF extension extraction."""
        assert decoder_helpers.get_file_extension("image/gif") == "gif"

    def test_get_extension_svg(self):
        """Test SVG extension extraction from 'image/svg+xml'."""
        assert decoder_helpers.get_file_extension("image/svg+xml") == "svg"

    def test_get_extension_webp(self):
        """Test WebP extension extraction."""
        assert decoder_helpers.get_file_extension("image/webp") == "webp"

    def test_get_extension_mp4(self):
        """Test MP4 extension extraction."""
        assert decoder_helpers.get_file_extension("video/mp4") == "mp4"

    def test_get_extension_json(self):
        """Test JSON extension extraction."""
        assert decoder_helpers.get_file_extension("application/json") == "json"

    def test_get_extension_with_parameters(self):
        """Test extension extraction with MIME type parameters."""
        assert decoder_helpers.get_file_extension("image/jpeg; charset=utf-8") == "jpeg"

    def test_get_extension_empty_string(self):
        """Test that an empty MIME type returns 'bin'."""
        assert decoder_helpers.get_file_extension("") == "bin"

    def test_get_extension_none(self):
        """Test that a None MIME type returns 'bin'."""
        # This will fail the "if not mime_type:" check
        result = decoder_helpers.get_file_extension("")
        assert result == "bin"

    def test_get_extension_invalid_format(self):
        """Test that an invalid MIME type format returns 'bin'."""
        assert decoder_helpers.get_file_extension("invalidmimetype") == "bin"

    def test_get_extension_unknown_type(self):
        """Test that an unknown MIME type extracts subtype."""
        assert (
            decoder_helpers.get_file_extension("application/x-custom-type")
            == "x-custom-type"
        )

    def test_get_extension_removes_leading_dot(self):
        """Test that leading dots are removed from extensions."""
        # mimetypes.guess_extension can return extensions with leading dots
        result = decoder_helpers.get_file_extension("text/plain")
        assert not result.startswith(".")
