from opik_optimizer.api_objects.chat_prompt import ChatPrompt


class TestChatPromptReplaceInMessages:
    """Tests for replace_in_messages method."""

    def test_replaces_in_string_content(self) -> None:
        """Should replace labels in string content."""
        prompt = ChatPrompt(system="Test")
        messages = [{"role": "user", "content": "Hello {name}!"}]

        result = prompt.replace_in_messages(messages, "{name}", "World")

        assert result[0]["content"] == "Hello World!"

    def test_replaces_in_content_parts(self) -> None:
        """Should replace labels in content parts list."""
        prompt = ChatPrompt(system="Test")
        messages = [
            {
                "role": "user",
                "content": [
                    {"type": "text", "text": "Hello {name}!"},
                ],
            }
        ]

        result = prompt.replace_in_messages(messages, "{name}", "World")

        assert result[0]["content"][0]["text"] == "Hello World!"

    def test_modifies_messages_in_place(self) -> None:
        """replace_in_messages modifies the passed list in place."""
        prompt = ChatPrompt(system="Test")
        messages = [{"role": "user", "content": "Hello {name}!"}]

        result = prompt.replace_in_messages(messages, "{name}", "World")

        assert result is messages
        assert messages[0]["content"] == "Hello World!"

