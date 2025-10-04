"""
Unit tests for image helper utilities.

Tests cover:
- Base64 encoding/decoding
- Image format detection
- Structured content conversion
- Image validation
- Token estimation
"""

import pytest
from PIL import Image
from io import BytesIO

from opik_optimizer.utils.image_helpers import (
    encode_pil_to_base64_uri,
    decode_base64_uri_to_pil,
    is_base64_data_uri,
    get_image_format_from_uri,
    convert_to_structured_content,
    extract_images_from_structured_content,
    validate_image_size,
    estimate_image_tokens,
)


def create_test_image(width=100, height=100, mode="RGB"):
    """Helper to create a test PIL Image."""
    return Image.new(mode, (width, height), color="red")


def test_encode_pil_to_base64_uri():
    """Test encoding PIL Image to base64 data URI."""
    img = create_test_image(50, 50)

    # Test PNG encoding
    uri = encode_pil_to_base64_uri(img, format="PNG")
    assert uri.startswith("data:image/png;base64,")
    assert len(uri) > 50  # Should have substantial content

    # Test JPEG encoding
    uri = encode_pil_to_base64_uri(img, format="JPEG", quality=85)
    assert uri.startswith("data:image/jpeg;base64,")


def test_decode_base64_uri_to_pil():
    """Test decoding base64 data URI back to PIL Image."""
    # Create and encode image
    original = create_test_image(50, 50)
    uri = encode_pil_to_base64_uri(original, format="PNG")

    # Decode
    decoded = decode_base64_uri_to_pil(uri)

    # Verify dimensions match
    assert decoded.size == original.size
    assert decoded.mode == original.mode


def test_is_base64_data_uri():
    """Test detection of base64 data URIs."""
    # Valid data URI
    assert is_base64_data_uri("data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg==")

    # Valid JPEG data URI
    assert is_base64_data_uri("data:image/jpeg;base64,/9j/4AAQSkZJRg==")

    # Invalid cases
    assert not is_base64_data_uri("https://example.com/image.jpg")
    assert not is_base64_data_uri("not a data uri")
    assert not is_base64_data_uri("")
    assert not is_base64_data_uri(None)
    assert not is_base64_data_uri(123)


def test_get_image_format_from_uri():
    """Test extracting image format from data URI."""
    assert get_image_format_from_uri("data:image/png;base64,abc") == "png"
    assert get_image_format_from_uri("data:image/jpeg;base64,abc") == "jpeg"
    assert get_image_format_from_uri("data:image/webp;base64,abc") == "webp"
    assert get_image_format_from_uri("not a data uri") is None
    assert get_image_format_from_uri("") is None


def test_convert_to_structured_content():
    """Test converting text and image to structured content format."""
    # Text only
    result = convert_to_structured_content("Hello world")
    assert len(result) == 1
    assert result[0]["type"] == "text"
    assert result[0]["text"] == "Hello world"

    # Text with image
    result = convert_to_structured_content(
        "What's this?",
        image_uri="data:image/png;base64,abc",
        image_detail="high"
    )
    assert len(result) == 2
    assert result[0]["type"] == "text"
    assert result[0]["text"] == "What's this?"
    assert result[1]["type"] == "image_url"
    assert result[1]["image_url"]["url"] == "data:image/png;base64,abc"
    assert result[1]["image_url"]["detail"] == "high"


def test_extract_images_from_structured_content():
    """Test extracting image URIs from structured content."""
    # Content with one image
    content = [
        {"type": "text", "text": "Compare these:"},
        {"type": "image_url", "image_url": {"url": "data:image/png;base64,abc"}},
    ]
    images = extract_images_from_structured_content(content)
    assert len(images) == 1
    assert images[0] == "data:image/png;base64,abc"

    # Content with multiple images
    content = [
        {"type": "text", "text": "Compare:"},
        {"type": "image_url", "image_url": {"url": "data:image/png;base64,abc"}},
        {"type": "text", "text": "vs"},
        {"type": "image_url", "image_url": {"url": "https://example.com/img.jpg"}},
    ]
    images = extract_images_from_structured_content(content)
    assert len(images) == 2
    assert "data:image/png;base64,abc" in images
    assert "https://example.com/img.jpg" in images

    # Content with no images
    content = [{"type": "text", "text": "Just text"}]
    images = extract_images_from_structured_content(content)
    assert len(images) == 0


def test_validate_image_size():
    """Test image size validation."""
    # Valid size
    img = create_test_image(1000, 1000)
    is_valid, error = validate_image_size(img, max_width=2048, max_height=2048)
    assert is_valid is True
    assert error is None

    # Width exceeds limit
    img = create_test_image(3000, 1000)
    is_valid, error = validate_image_size(img, max_width=2048, max_height=2048)
    assert is_valid is False
    assert "width" in error.lower()
    assert "3000" in error
    assert "2048" in error

    # Height exceeds limit
    img = create_test_image(1000, 3000)
    is_valid, error = validate_image_size(img, max_width=2048, max_height=2048)
    assert is_valid is False
    assert "height" in error.lower()
    assert "3000" in error

    # Both exceed limit
    img = create_test_image(3000, 3000)
    is_valid, error = validate_image_size(img, max_width=2048, max_height=2048)
    assert is_valid is False


def test_estimate_image_tokens():
    """Test token estimation for different models."""
    # Small image with gpt-4o
    img = create_test_image(400, 400)
    tokens = estimate_image_tokens(img, model="gpt-4o")
    assert tokens == 85  # Single tile

    # Larger image with gpt-4o
    img = create_test_image(1024, 1024)
    tokens = estimate_image_tokens(img, model="gpt-4o-mini")
    assert tokens > 85  # Multiple tiles

    # Claude model
    img = create_test_image(512, 512)
    tokens = estimate_image_tokens(img, model="claude-3-opus")
    assert tokens == 1600

    # Gemini model
    img = create_test_image(512, 512)
    tokens = estimate_image_tokens(img, model="gemini-1.5-pro")
    assert tokens == 258

    # Unknown model (default)
    img = create_test_image(512, 512)
    tokens = estimate_image_tokens(img, model="unknown-model")
    assert tokens == 500


def test_roundtrip_encoding():
    """Test encoding and decoding preserves image data."""
    # Create original image with specific pattern
    original = create_test_image(100, 100)

    # Encode to URI
    uri = encode_pil_to_base64_uri(original, format="PNG")

    # Decode back
    decoded = decode_base64_uri_to_pil(uri)

    # Verify properties match
    assert decoded.size == original.size
    assert decoded.mode == original.mode

    # For PNG, pixels should match exactly
    original_pixels = list(original.getdata())
    decoded_pixels = list(decoded.getdata())
    assert original_pixels == decoded_pixels
