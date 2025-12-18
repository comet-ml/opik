import pytest
from opik.validation import chat_prompt_messages


@pytest.mark.parametrize(
    argnames="messages, is_valid",
    argvalues=[
        # Valid cases
        ([{"role": "system", "content": "You are helpful"}], True),
        ([{"role": "user", "content": "Hello"}], True),
        ([{"role": "assistant", "content": "Hi there"}], True),
        (
            [
                {"role": "system", "content": "You are helpful"},
                {"role": "user", "content": "Hello"},
                {"role": "assistant", "content": "Hi"},
            ],
            True,
        ),
        (
            [
                {
                    "role": "user",
                    "content": [
                        {"type": "text", "text": "Hello"},
                        {
                            "type": "image_url",
                            "image_url": {"url": "https://example.com/image.jpg"},
                        },
                    ],
                }
            ],
            True,
        ),
        (
            [
                {"role": "system", "content": "You are helpful"},
                {"role": "user", "content": [{"type": "text", "text": "Hello"}]},
            ],
            True,
        ),
        ([], True),  # Empty list is valid
        # Invalid cases - not a list
        (None, False),
        ("not a list", False),
        (123, False),
        ({"role": "user", "content": "hello"}, False),  # Single dict instead of list
        # Invalid cases - message structure
        (["not a dict"], False),
        ([{"content": "hello"}], False),  # Missing role
        ([{"role": "user"}], False),  # Missing content
        (
            [{"role": "user", "content": "hello", "extra": "key"}],
            False,
        ),  # Extra key
        ([{}], False),  # Empty dict
        # Invalid cases - role values
        ([{"role": "invalid", "content": "hello"}], False),
        ([{"role": "System", "content": "hello"}], False),  # Wrong case
        ([{"role": "", "content": "hello"}], False),  # Empty string
        ([{"role": None, "content": "hello"}], False),  # None
        ([{"role": 123, "content": "hello"}], False),  # Wrong type
        # Invalid cases - content types
        ([{"role": "user", "content": None}], False),
        ([{"role": "user", "content": 123}], False),  # Wrong type
        ([{"role": "user", "content": ["not", "dicts"]}], False),  # List of non-dicts
        ([{"role": "user", "content": []}], True),  # Empty list is valid
        # Invalid cases - content parts (when content is array)
        ([{"role": "user", "content": [{"text": "hello"}]}], False),  # Missing type
        ([{"role": "user", "content": [{}]}], False),  # Empty dict in array
        ([{"role": "user", "content": ["not a dict"]}], False),  # Non-dict in array
        # Multiple validation errors
        (
            [
                {"role": "invalid", "content": "hello"},
                {"role": "user", "content": None},
                {"role": "user", "content": [{"text": "hello"}]},
            ],
            False,
        ),
        # Valid cases - content list with type-specific keys
        (
            [{"role": "user", "content": [{"type": "text", "text": "Hello"}]}],
            True,
        ),
        (
            [
                {
                    "role": "user",
                    "content": [
                        {
                            "type": "image_url",
                            "image_url": {"url": "https://example.com/image.jpg"},
                        }
                    ],
                }
            ],
            True,
        ),
        (
            [
                {
                    "role": "user",
                    "content": [
                        {
                            "type": "video_url",
                            "video_url": {"url": "https://example.com/video.mp4"},
                        }
                    ],
                }
            ],
            True,
        ),
        (
            [
                {
                    "role": "user",
                    "content": [
                        {
                            "type": "audio_url",
                            "audio_url": {"url": "https://example.com/audio.mp3"},
                        }
                    ],
                }
            ],
            True,
        ),
        (
            [
                {
                    "role": "user",
                    "content": [
                        {"type": "text", "text": "Hello"},
                        {
                            "type": "image_url",
                            "image_url": {"url": "https://example.com/image.jpg"},
                        },
                        {
                            "type": "video_url",
                            "video_url": {"url": "https://example.com/video.mp4"},
                        },
                    ],
                }
            ],
            True,
        ),
        # Invalid cases - missing required keys for specific types
        (
            [{"role": "user", "content": [{"type": "text"}]}],
            False,  # Missing text key
        ),
        (
            [{"role": "user", "content": [{"type": "image_url"}]}],
            False,  # Missing image_url key
        ),
        (
            [{"role": "user", "content": [{"type": "video_url"}]}],
            False,  # Missing video_url key
        ),
        (
            [{"role": "user", "content": [{"type": "audio_url"}]}],
            False,  # Missing audio_url key
        ),
        # Invalid cases - wrong type for URL objects
        (
            [{"role": "user", "content": [{"type": "image_url", "image_url": 123}]}],
            False,  # image_url is not a dict
        ),
        (
            [{"role": "user", "content": [{"type": "image_url", "image_url": None}]}],
            False,  # image_url is None
        ),
        (
            [
                {
                    "role": "user",
                    "content": [{"type": "image_url", "image_url": "string"}],
                }
            ],
            False,  # image_url is a string, should be dict
        ),
        (
            [{"role": "user", "content": [{"type": "image_url", "image_url": {}}]}],
            False,  # image_url dict missing 'url' key
        ),
        (
            [
                {
                    "role": "user",
                    "content": [{"type": "image_url", "image_url": {"url": 123}}],
                }
            ],
            False,  # image_url.url is not a string
        ),
        (
            [{"role": "user", "content": [{"type": "video_url", "video_url": 123}]}],
            False,  # video_url is not a dict
        ),
        (
            [{"role": "user", "content": [{"type": "video_url", "video_url": {}}]}],
            False,  # video_url dict missing 'url' key
        ),
        (
            [
                {
                    "role": "user",
                    "content": [
                        {"type": "audio_url", "audio_url": ["not", "a", "dict"]}
                    ],
                }
            ],
            False,  # audio_url is not a dict
        ),
        (
            [{"role": "user", "content": [{"type": "audio_url", "audio_url": {}}]}],
            False,  # audio_url dict missing 'url' key
        ),
        (
            [
                {
                    "role": "user",
                    "content": [{"type": "audio_url", "audio_url": {"url": None}}],
                }
            ],
            False,  # audio_url.url is None
        ),
        (
            [{"role": "user", "content": [{"type": "text", "text": 123}]}],
            False,  # text is not a string
        ),
        (
            [{"role": "user", "content": [{"type": "text", "text": None}]}],
            False,  # text is None
        ),
        # Invalid cases - wrong key for type
        (
            [{"role": "user", "content": [{"type": "image_url", "text": "Hello"}]}],
            False,  # Has text but missing image_url
        ),
        (
            [
                {
                    "role": "user",
                    "content": [
                        {
                            "type": "text",
                            "image_url": {"url": "https://example.com/image.jpg"},
                        }
                    ],
                }
            ],
            False,  # Has image_url but missing text
        ),
    ],
)
def test_chat_prompt_messages_validator(messages, is_valid):
    validator = chat_prompt_messages.ChatPromptMessagesValidator(messages)
    assert validator.validate().ok() is is_valid, f"Failed with {messages}"


def test_chat_prompt_messages_validator_error_messages():
    """Test that error messages include message indices."""
    validator = chat_prompt_messages.ChatPromptMessagesValidator(
        [
            {"role": "invalid", "content": "hello"},
            {"role": "user", "content": None},
        ]
    )
    result = validator.validate()
    assert result.failed() is True
    assert len(result.failure_reasons) > 0
    # Check that error messages include indices
    assert any("messages[0]" in reason for reason in result.failure_reasons)
    assert any("messages[1]" in reason for reason in result.failure_reasons)


def test_chat_prompt_messages_validator_raise_validation_error():
    """Test that raise_if_validation_failed raises ValidationError."""
    import opik.exceptions

    validator = chat_prompt_messages.ChatPromptMessagesValidator(
        [{"role": "invalid", "content": "hello"}]
    )
    validator.validate()

    with pytest.raises(opik.exceptions.ValidationError) as exc_info:
        validator.raise_if_validation_failed()

    assert "ChatPrompt.__init__" in str(exc_info.value)
    assert "messages[0].role" in str(exc_info.value)
