"""
Unit tests for ChatPrompt multimodal support.

Tests cover:
- Structured content validation
- Template variable substitution in structured content
- Backward compatibility with string content
- Mixed string and structured content handling
"""

import pytest
from opik_optimizer.optimization_config.chat_prompt import ChatPrompt


def test_string_content_backward_compatibility():
    """Test that string content still works (backward compatibility)."""
    prompt = ChatPrompt(
        system="You are a helpful assistant",
        user="Hello {name}",
    )

    messages = prompt.get_messages(dataset_item={"name": "World"})

    assert len(messages) == 2
    assert messages[0]["role"] == "system"
    assert messages[0]["content"] == "You are a helpful assistant"
    assert messages[1]["role"] == "user"
    assert messages[1]["content"] == "Hello World"


def test_structured_content_creation():
    """Test creating ChatPrompt with structured content."""
    structured_content = [
        {"type": "text", "text": "What's in this image?"},
        {"type": "image_url", "image_url": {"url": "data:image/png;base64,abc"}},
    ]

    prompt = ChatPrompt(
        messages=[
            {"role": "user", "content": structured_content}
        ]
    )

    messages = prompt.get_messages()

    assert len(messages) == 1
    assert messages[0]["role"] == "user"
    assert isinstance(messages[0]["content"], list)
    assert len(messages[0]["content"]) == 2
    assert messages[0]["content"][0]["type"] == "text"
    assert messages[0]["content"][1]["type"] == "image_url"


def test_structured_content_validation_valid():
    """Test that valid structured content passes validation."""
    # Valid text part
    structured_content = [
        {"type": "text", "text": "Hello"},
    ]
    prompt = ChatPrompt(messages=[{"role": "user", "content": structured_content}])
    assert prompt is not None

    # Valid image part
    structured_content = [
        {"type": "image_url", "image_url": {"url": "data:image/png;base64,abc"}},
    ]
    prompt = ChatPrompt(messages=[{"role": "user", "content": structured_content}])
    assert prompt is not None

    # Valid mixed content
    structured_content = [
        {"type": "text", "text": "What's this?"},
        {"type": "image_url", "image_url": {"url": "data:image/png;base64,abc"}},
    ]
    prompt = ChatPrompt(messages=[{"role": "user", "content": structured_content}])
    assert prompt is not None


def test_structured_content_validation_invalid():
    """Test that invalid structured content raises errors."""
    # Invalid type
    with pytest.raises(ValueError, match="Invalid content type"):
        ChatPrompt(messages=[{
            "role": "user",
            "content": [{"type": "video", "url": "..."}]
        }])

    # Text part missing 'text' field
    with pytest.raises(ValueError, match="Text part must have 'text' field"):
        ChatPrompt(messages=[{
            "role": "user",
            "content": [{"type": "text"}]
        }])

    # Image part missing 'image_url' field
    with pytest.raises(ValueError, match="Image part must have 'image_url' field"):
        ChatPrompt(messages=[{
            "role": "user",
            "content": [{"type": "image_url"}]
        }])

    # Non-dict part
    with pytest.raises(ValueError, match="must be dictionaries"):
        ChatPrompt(messages=[{
            "role": "user",
            "content": ["not a dict"]
        }])


def test_template_substitution_in_text():
    """Test template variable substitution in text parts."""
    structured_content = [
        {"type": "text", "text": "Hello {name}, how are you?"},
        {"type": "image_url", "image_url": {"url": "data:image/png;base64,abc"}},
    ]

    prompt = ChatPrompt(messages=[{"role": "user", "content": structured_content}])
    messages = prompt.get_messages(dataset_item={"name": "Alice"})

    assert messages[0]["content"][0]["text"] == "Hello Alice, how are you?"
    # Image should be preserved
    assert messages[0]["content"][1]["type"] == "image_url"


def test_template_substitution_in_image_url():
    """Test template variable substitution in image URL parts."""
    structured_content = [
        {"type": "text", "text": "Analyze this:"},
        {"type": "image_url", "image_url": {"url": "{image_uri}"}},
    ]

    prompt = ChatPrompt(messages=[{"role": "user", "content": structured_content}])
    messages = prompt.get_messages(dataset_item={
        "image_uri": "data:image/png;base64,xyz"
    })

    assert messages[0]["content"][1]["image_url"]["url"] == "data:image/png;base64,xyz"


def test_template_substitution_multiple_variables():
    """Test substitution of multiple variables in structured content."""
    structured_content = [
        {"type": "text", "text": "{question}"},
        {"type": "image_url", "image_url": {"url": "{image}"}},
    ]

    prompt = ChatPrompt(messages=[{"role": "user", "content": structured_content}])
    messages = prompt.get_messages(dataset_item={
        "question": "What's in this image?",
        "image": "data:image/png;base64,abc",
    })

    assert messages[0]["content"][0]["text"] == "What's in this image?"
    assert messages[0]["content"][1]["image_url"]["url"] == "data:image/png;base64,abc"


def test_mixed_messages_string_and_structured():
    """Test ChatPrompt with mixed string and structured content messages."""
    prompt = ChatPrompt(
        messages=[
            {"role": "system", "content": "You are an assistant"},
            {
                "role": "user",
                "content": [
                    {"type": "text", "text": "Analyze {image_name}"},
                    {"type": "image_url", "image_url": {"url": "{image_uri}"}},
                ]
            },
        ]
    )

    messages = prompt.get_messages(dataset_item={
        "image_name": "photo.jpg",
        "image_uri": "data:image/png;base64,xyz",
    })

    # System message should remain string
    assert isinstance(messages[0]["content"], str)
    assert messages[0]["content"] == "You are an assistant"

    # User message should be structured with substitutions
    assert isinstance(messages[1]["content"], list)
    assert messages[1]["content"][0]["text"] == "Analyze photo.jpg"
    assert messages[1]["content"][1]["image_url"]["url"] == "data:image/png;base64,xyz"


def test_copy_preserves_structured_content():
    """Test that copying a ChatPrompt preserves structured content."""
    structured_content = [
        {"type": "text", "text": "Hello"},
        {"type": "image_url", "image_url": {"url": "data:image/png;base64,abc"}},
    ]

    original = ChatPrompt(messages=[{"role": "user", "content": structured_content}])
    copied = original.copy()

    # Verify copy has the same structured content
    original_messages = original.get_messages()
    copied_messages = copied.get_messages()

    assert len(copied_messages) == len(original_messages)
    assert copied_messages[0]["content"][0]["type"] == "text"
    assert copied_messages[0]["content"][1]["type"] == "image_url"

    # Verify they are independent (modifying copy doesn't affect original)
    copied.set_messages([{"role": "user", "content": "Different"}])
    assert original.get_messages()[0]["content"] != copied.get_messages()[0]["content"]


def test_to_dict_with_structured_content():
    """Test serialization of ChatPrompt with structured content."""
    structured_content = [
        {"type": "text", "text": "Question"},
        {"type": "image_url", "image_url": {"url": "data:image/png;base64,abc"}},
    ]

    prompt = ChatPrompt(messages=[{"role": "user", "content": structured_content}])
    result = prompt.to_dict()

    assert "messages" in result
    assert isinstance(result["messages"], list)
    assert isinstance(result["messages"][0]["content"], list)


def test_image_detail_preservation():
    """Test that image detail field is preserved in structured content."""
    structured_content = [
        {"type": "text", "text": "Analyze"},
        {
            "type": "image_url",
            "image_url": {
                "url": "data:image/png;base64,abc",
                "detail": "high",
            }
        },
    ]

    prompt = ChatPrompt(messages=[{"role": "user", "content": structured_content}])
    messages = prompt.get_messages()

    assert messages[0]["content"][1]["image_url"]["detail"] == "high"
