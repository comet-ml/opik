"""
Unit tests for multimodal mutation and crossover operations.

Tests ensure that:
- Images are preserved during mutations
- Text parts are correctly mutated
- Crossover operations maintain structured content integrity
- Backward compatibility with string content is maintained
"""

import pytest
from opik_optimizer.utils.message_content import (
    extract_text_from_content,
    is_multimodal_prompt,
    rebuild_content_with_text,
)
from opik_optimizer.utils.message_content import (
    extract_text_from_content as extract_text_crossover,
    rebuild_content_with_text as rebuild_content_crossover,
)


# Test helper functions for mutation operations


def test_extract_text_from_string():
    """Test extracting text from simple string content."""
    content = "Hello world"
    result = extract_text_from_content(content)
    assert result == "Hello world"


def test_extract_text_from_structured_content():
    """Test extracting text from structured multimodal content."""
    content = [
        {"type": "text", "text": "What's in this image?"},
        {"type": "image_url", "image_url": {"url": "data:image/png;base64,abc"}},
    ]

    result = extract_text_from_content(content)
    assert result == "What's in this image?"
    assert "data:image" not in result  # Image should not be in extracted text


def test_extract_text_from_multiple_text_parts():
    """Test extracting text when there are multiple text parts."""
    content = [
        {"type": "text", "text": "First part"},
        {"type": "image_url", "image_url": {"url": "data:image/png;base64,abc"}},
        {"type": "text", "text": "Second part"},
    ]

    result = extract_text_from_content(content)
    assert "First part" in result
    assert "Second part" in result


def test_extract_text_handles_non_standard_input():
    """Test that extract_text handles edge cases gracefully."""
    # Empty list
    assert extract_text_from_content([]) == ""

    # List with no text parts
    content = [
        {"type": "image_url", "image_url": {"url": "data:image/png;base64,abc"}},
    ]
    assert extract_text_from_content(content) == ""

    # Non-string, non-list (should convert to string)
    assert extract_text_from_content(123) == "123"


def test_rebuild_content_with_string():
    """Test rebuilding simple string content."""
    original = "Hello world"
    mutated_text = "Greetings world"

    result = rebuild_content_with_text(original, mutated_text)
    assert result == "Greetings world"
    assert isinstance(result, str)


def test_rebuild_content_preserves_images():
    """Test that rebuilding structured content preserves images."""
    original = [
        {"type": "text", "text": "Original text"},
        {"type": "image_url", "image_url": {"url": "data:image/png;base64,abc"}},
    ]

    mutated_text = "Mutated text"
    result = rebuild_content_with_text(original, mutated_text)

    # Should be a list (structured content)
    assert isinstance(result, list)
    assert len(result) == 2

    # First part should have mutated text
    assert result[0]["type"] == "text"
    assert result[0]["text"] == "Mutated text"

    # Second part should preserve image
    assert result[1]["type"] == "image_url"
    assert result[1]["image_url"]["url"] == "data:image/png;base64,abc"


def test_rebuild_content_preserves_multiple_images():
    """Test that multiple images are all preserved."""
    original = [
        {"type": "text", "text": "Compare these images"},
        {"type": "image_url", "image_url": {"url": "data:image/png;base64,img1"}},
        {"type": "image_url", "image_url": {"url": "data:image/png;base64,img2"}},
    ]

    mutated_text = "Analyze both images"
    result = rebuild_content_with_text(original, mutated_text)

    assert len(result) == 3
    assert result[0]["text"] == "Analyze both images"
    assert result[1]["image_url"]["url"] == "data:image/png;base64,img1"
    assert result[2]["image_url"]["url"] == "data:image/png;base64,img2"


def test_rebuild_content_preserves_image_detail():
    """Test that image detail field is preserved during rebuild."""
    original = [
        {"type": "text", "text": "Original"},
        {
            "type": "image_url",
            "image_url": {
                "url": "data:image/png;base64,abc",
                "detail": "high"
            }
        },
    ]

    result = rebuild_content_with_text(original, "Mutated")

    assert result[1]["image_url"]["detail"] == "high"


# Test crossover helper functions (should have identical behavior)


def test_crossover_extract_text_from_structured():
    """Test that crossover's extract function works correctly."""
    content = [
        {"type": "text", "text": "Crossover text"},
        {"type": "image_url", "image_url": {"url": "data:image/png;base64,xyz"}},
    ]

    result = extract_text_crossover(content)
    assert result == "Crossover text"


def test_crossover_rebuild_preserves_images():
    """Test that crossover's rebuild function preserves images."""
    original = [
        {"type": "text", "text": "Parent text"},
        {"type": "image_url", "image_url": {"url": "data:image/png;base64,parent"}},
    ]

    result = rebuild_content_crossover(original, "Child text")

    assert isinstance(result, list)
    assert result[0]["text"] == "Child text"
    assert result[1]["image_url"]["url"] == "data:image/png;base64,parent"


def test_mutation_preserves_original_object():
    """Test that mutation helpers don't modify original content object."""
    original = [
        {"type": "text", "text": "Original"},
        {"type": "image_url", "image_url": {"url": "data:image/png;base64,abc"}},
    ]

    # Make a copy to compare later
    original_text = original[0]["text"]
    original_url = original[1]["image_url"]["url"]

    # Perform operations
    extracted = extract_text_from_content(original)
    rebuilt = rebuild_content_with_text(original, "Mutated")

    # Original should be unchanged
    assert original[0]["text"] == original_text
    assert original[1]["image_url"]["url"] == original_url


def test_empty_text_mutation():
    """Test handling of empty text in mutations."""
    original = [
        {"type": "text", "text": ""},
        {"type": "image_url", "image_url": {"url": "data:image/png;base64,abc"}},
    ]

    result = rebuild_content_with_text(original, "New text")

    assert result[0]["text"] == "New text"
    assert len(result) == 2  # Image should still be present


def test_rebuild_content_preserves_video_and_file_parts():
    """Ensure non-image multimodal parts survive rebuilds."""
    original = [
        {"type": "text", "text": "Process these assets"},
        {"type": "video_url", "video_url": {"url": "https://example.com/clip.mp4"}},
        {"type": "file_url", "file_url": {"url": "https://example.com/report.pdf"}},
    ]

    rebuilt = rebuild_content_with_text(original, "Updated instructions")

    assert isinstance(rebuilt, list)
    assert rebuilt[0]["text"] == "Updated instructions"
    assert rebuilt[1]["type"] == "video_url"
    assert rebuilt[1]["video_url"]["url"].endswith("clip.mp4")
    assert rebuilt[2]["type"] == "file_url"
    assert rebuilt[2]["file_url"]["url"].endswith("report.pdf")


def test_is_multimodal_prompt_detects_video_and_file_parts():
    """Video/file attachments should mark the prompt as multimodal."""
    messages = [
        {
            "role": "user",
            "content": [
                {"type": "text", "text": "Review the clip and document"},
                {"type": "video_url", "video_url": {"url": "https://example.com/clip.mp4"}},
                {"type": "file_url", "file_url": {"url": "https://example.com/report.pdf"}},
            ],
        }
    ]

    assert is_multimodal_prompt(messages) is True


def test_is_multimodal_prompt_detects_placeholders():
    """String placeholders for video/file attachments should also count."""
    template = "Please watch {video_asset} and read {file_brief}"
    assert is_multimodal_prompt(template) is True


def test_text_only_structured_content():
    """Test structured content with only text parts (no images)."""
    original = [
        {"type": "text", "text": "Just text"},
    ]

    extracted = extract_text_from_content(original)
    assert extracted == "Just text"

    rebuilt = rebuild_content_with_text(original, "Mutated text")
    # Should still be structured format
    assert isinstance(rebuilt, list)
    assert rebuilt[0]["type"] == "text"
    assert rebuilt[0]["text"] == "Mutated text"


def test_roundtrip_text_extraction_and_rebuild():
    """Test that extracting and rebuilding maintains structural integrity."""
    original = [
        {"type": "text", "text": "Hello world"},
        {"type": "image_url", "image_url": {"url": "data:image/png;base64,img1"}},
        {"type": "image_url", "image_url": {"url": "data:image/png;base64,img2"}},
    ]

    # Extract text
    extracted = extract_text_from_content(original)

    # Rebuild with same text
    rebuilt = rebuild_content_with_text(original, extracted)

    # Should have same structure: 1 text + 2 images
    assert len(rebuilt) == 3
    assert rebuilt[0]["type"] == "text"
    assert rebuilt[1]["type"] == "image_url"
    assert rebuilt[2]["type"] == "image_url"
