from __future__ import annotations


from opik_optimizer.api_objects.chat_prompt import ChatPrompt


class TestChatPromptSerialization:
    """Tests for serialization methods."""

    def test_to_dict_includes_system(self) -> None:
        """to_dict should include system when present."""
        prompt = ChatPrompt(system="System message")
        result = prompt.to_dict()

        assert result == {"system": "System message"}

    def test_to_dict_includes_user(self) -> None:
        """to_dict should include user when present."""
        prompt = ChatPrompt(user="User message")
        result = prompt.to_dict()

        assert result == {"user": "User message"}

    def test_to_dict_includes_messages(self) -> None:
        """to_dict should include messages when present."""
        messages = [{"role": "user", "content": "Hello"}]
        prompt = ChatPrompt(messages=messages)
        result = prompt.to_dict()

        assert result == {"messages": messages}

    def test_to_dict_includes_all_fields(self) -> None:
        """to_dict should include all non-None fields."""
        prompt = ChatPrompt(system="System", user="User")
        result = prompt.to_dict()

        assert result == {"system": "System", "user": "User"}


class TestChatPromptCopy:
    """Tests for copy() method."""

    def test_copy_creates_new_instance(self) -> None:
        """copy should create a new ChatPrompt instance."""
        original = ChatPrompt(system="Original system", user="Original user")
        copied = original.copy()

        assert copied is not original
        assert copied.system == original.system
        assert copied.user == original.user

    def test_copy_preserves_model_settings(self) -> None:
        """copy should preserve model and model_kwargs."""
        original = ChatPrompt(
            system="Test",
            model="gpt-4",
            model_parameters={"temperature": 0.5},
        )
        copied = original.copy()

        assert copied.model == "gpt-4"
        assert copied.model_kwargs == {"temperature": 0.5}

    def test_copy_deep_copies_messages(self) -> None:
        """copy should deep copy messages list."""
        original = ChatPrompt(messages=[{"role": "user", "content": "Hello"}])
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

    def test_copy_deep_copies_model_kwargs(self) -> None:
        """copy should deep copy model_kwargs."""
        original = ChatPrompt(
            system="Test",
            model_parameters={"nested": {"key": "value"}},
        )
        copied = original.copy()

        assert copied.model_kwargs is not None
        copied.model_kwargs["nested"]["key"] = "modified"

        assert original.model_kwargs is not None
        assert original.model_kwargs["nested"]["key"] == "value"


class TestChatPromptSetMessages:
    """Tests for set_messages() method."""

    def test_replaces_existing_content(self) -> None:
        """set_messages should replace all existing content."""
        prompt = ChatPrompt(system="Old system", user="Old user")
        new_messages = [{"role": "assistant", "content": "New content"}]

        prompt.set_messages(new_messages)

        assert prompt.system is None
        assert prompt.user is None
        assert prompt.messages == new_messages

    def test_deep_copies_messages(self) -> None:
        """set_messages should deep copy the input."""
        prompt = ChatPrompt(system="Test")
        messages = [{"role": "user", "content": "Original"}]

        prompt.set_messages(messages)
        messages[0]["content"] = "Modified"

        assert prompt.messages is not None
        assert prompt.messages[0]["content"] == "Original"


class TestChatPromptModelValidate:
    """Tests for model_validate class method."""

    def test_creates_from_dict_with_system(self) -> None:
        """model_validate should create prompt from dict with system."""
        data = {"system": "System message"}
        prompt = ChatPrompt.model_validate(data)

        assert prompt.system == "System message"

    def test_creates_from_dict_with_user(self) -> None:
        """model_validate should create prompt from dict with user."""
        data = {"user": "User message"}
        prompt = ChatPrompt.model_validate(data)

        assert prompt.user == "User message"

    def test_creates_from_dict_with_messages(self) -> None:
        """model_validate should create prompt from dict with messages."""
        data = {"messages": [{"role": "user", "content": "Hello"}]}
        prompt = ChatPrompt.model_validate(data)

        assert prompt.messages == [{"role": "user", "content": "Hello"}]
