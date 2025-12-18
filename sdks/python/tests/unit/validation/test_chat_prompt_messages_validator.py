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
                        {"type": "image", "image_url": "..."},
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
    """Test that raise_validation_error raises ValidationError."""
    import opik.exceptions

    validator = chat_prompt_messages.ChatPromptMessagesValidator(
        [{"role": "invalid", "content": "hello"}]
    )
    validator.validate()

    with pytest.raises(opik.exceptions.ValidationError) as exc_info:
        validator.raise_validation_error()

    assert "ChatPrompt.__init__" in str(exc_info.value)
    assert "messages[0].role" in str(exc_info.value)

