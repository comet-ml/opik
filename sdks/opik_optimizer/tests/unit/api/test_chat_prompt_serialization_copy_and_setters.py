from __future__ import annotations


from opik_optimizer.api_objects.chat_prompt import ChatPrompt
from tests.unit.fixtures import user_message


class TestChatPromptCopy:
    """Tests for copy() method."""

    def test_copy_deep_copies_messages(self) -> None:
        """copy should deep copy messages list."""
        original = ChatPrompt(messages=[user_message("Hello")])
        copied = original.copy()

        assert copied.messages is not None
        copied.messages[0]["content"] = "Modified"

        assert original.messages is not None
        assert original.messages[0]["content"] == "Hello"

    def test_copy_deep_copies_tools(self) -> None:
        """copy should deep copy tools list."""
        tools = [
            {
                "type": "function",
                "function": {
                    "name": "test",
                    "description": "Test tool",
                    "parameters": {"type": "object", "properties": {}},
                },
            }
        ]
        original = ChatPrompt(system="Use tools", tools=tools)
        copied = original.copy()

        assert copied.tools is not None
        copied.tools[0]["function"]["name"] = "modified"

        assert original.tools is not None
        assert original.tools[0]["function"]["name"] == "test"


class TestChatPromptModelValidate:
    """Tests for model_validate class method."""

    def test_creates_from_dict_with_messages(self) -> None:
        """model_validate should create prompt from dict with messages."""
        data = {"messages": [user_message("Hello")]}
        prompt = ChatPrompt.model_validate(data)

        assert prompt.messages == [user_message("Hello")]
