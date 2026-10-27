import pytest

from opik_optimizer.api_objects.chat_prompt import ChatPrompt
from tests.unit.fixtures import user_message


def test_rejects_empty_text_message() -> None:
    with pytest.raises(ValueError):
        ChatPrompt(messages=[user_message("   ")])


def test_rejects_empty_multimodal_message() -> None:
    with pytest.raises(ValueError):
        ChatPrompt(messages=[user_message([])])

    with pytest.raises(ValueError):
        ChatPrompt(messages=[user_message([{"type": "text", "text": "   \n"}])])

    with pytest.raises(ValueError):
        ChatPrompt(
            messages=[
                user_message(
                    [
                        {
                            "type": "image_url",
                            "image_url": {"url": "   ", "detail": "high"},
                        }
                    ]
                )
            ]
        )


def test_get_messages_with_content_parts() -> None:
    prompt = ChatPrompt(
        messages=[
            user_message(
                [
                    {"type": "text", "text": "Question about {topic}:"},
                    {
                        "type": "image_url",
                        "image_url": {"url": "{image_url}", "detail": "high"},
                    },
                    {"type": "text", "text": "What do you see?"},
                ]
            )
        ]
    )

    messages = prompt.get_messages(
        dataset_item={"topic": "this diagram", "image_url": "data:image/png;base64,abc"}
    )

    assert messages[0]["content"][0]["text"] == "Question about this diagram:"
    assert messages[0]["content"][1]["image_url"]["url"] == "data:image/png;base64,abc"
    assert messages[0]["content"][2]["text"] == "What do you see?"


def test_rejects_remote_mcp_without_url() -> None:
    with pytest.raises(ValueError):
        ChatPrompt(
            system="System",
            tools=[
                {
                    "mcp": {
                        "name": "remote_docs",
                        "server": {"type": "remote"},
                        "tool": {"name": "search-docs"},
                    }
                }
            ],
        )


class TestChatPromptEdgeCases:
    """Edge case tests for ChatPrompt."""

    def test_handles_non_string_dataset_values(self) -> None:
        """Should convert non-string dataset values to strings."""
        prompt = ChatPrompt(user="Count is {count}")

        messages = prompt.get_messages(dataset_item={"count": 42})

        assert messages[0]["content"] == "Count is 42"

    def test_handles_curly_braces_in_values(self) -> None:
        """Should handle curly braces in replacement values."""
        prompt = ChatPrompt(user="Code: {code}")

        messages = prompt.get_messages(dataset_item={"code": "{x: 1}"})

        assert messages[0]["content"] == "Code: {x: 1}"
