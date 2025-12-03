"""
Unit tests for opik_optimizer.api_objects.chat_prompt.ChatPrompt.

Tests cover:
- Initialization and validation
- Message merging from system/user/messages
- get_messages() with placeholder replacement
- replace_in_messages() with multimodal content
- copy() and to_dict() serialization
- Edge cases for multimodal content handling
"""

import pytest
from opik_optimizer.api_objects.chat_prompt import ChatPrompt


class TestChatPromptInitialization:
    """Tests for ChatPrompt initialization and validation."""

    def test_creates_with_system_only(self) -> None:
        """Should allow creation with just a system message."""
        prompt = ChatPrompt(system="You are a helpful assistant.")

        assert prompt.system == "You are a helpful assistant."
        assert prompt.user is None
        assert prompt.messages is None

    def test_creates_with_user_only(self) -> None:
        """Should allow creation with just a user message."""
        prompt = ChatPrompt(user="Hello, {name}!")

        assert prompt.system is None
        assert prompt.user == "Hello, {name}!"
        assert prompt.messages is None

    def test_creates_with_messages_only(self) -> None:
        """Should allow creation with just a messages list."""
        messages = [
            {"role": "system", "content": "Be concise."},
            {"role": "user", "content": "{question}"},
        ]
        prompt = ChatPrompt(messages=messages)

        assert prompt.system is None
        assert prompt.user is None
        assert prompt.messages == messages

    def test_creates_with_system_and_user(self) -> None:
        """Should allow creation with both system and user."""
        prompt = ChatPrompt(
            system="You are a helpful assistant.",
            user="What is {topic}?",
        )

        assert prompt.system == "You are a helpful assistant."
        assert prompt.user == "What is {topic}?"

    def test_raises_when_no_content_provided(self) -> None:
        """Should raise ValueError when no content is provided."""
        with pytest.raises(ValueError, match="At least one of"):
            ChatPrompt()

    def test_raises_when_user_and_messages_both_provided(self) -> None:
        """Should raise ValueError when both user and messages are provided."""
        with pytest.raises(ValueError, match="cannot be provided together"):
            ChatPrompt(
                user="Hello",
                messages=[{"role": "user", "content": "Hi"}],
            )

    def test_raises_when_system_and_messages_both_provided(self) -> None:
        """Should raise ValueError when both system and messages are provided."""
        with pytest.raises(ValueError, match="cannot be provided together"):
            ChatPrompt(
                system="System message",
                messages=[{"role": "system", "content": "Different system"}],
            )

    def test_raises_when_system_is_not_string(self) -> None:
        """Should raise ValueError when system is not a string."""
        with pytest.raises(ValueError, match="`system` must be a string"):
            ChatPrompt(system=123)  # type: ignore

    def test_raises_when_user_is_not_string(self) -> None:
        """Should raise ValueError when user is not a string."""
        with pytest.raises(ValueError, match="`user` must be a string"):
            ChatPrompt(user=["not", "a", "string"])  # type: ignore

    def test_validates_messages_format(self) -> None:
        """Should validate message format using Pydantic."""
        with pytest.raises(Exception):  # Pydantic ValidationError
            ChatPrompt(messages=[{"invalid": "format"}])

    def test_validates_tools_format(self) -> None:
        """Should validate tool format using Pydantic."""
        with pytest.raises(Exception):  # Pydantic ValidationError
            ChatPrompt(
                system="Use tools.",
                tools=[{"invalid": "tool format"}],
            )

    def test_sets_default_name(self) -> None:
        """Should set default name to 'chat-prompt'."""
        prompt = ChatPrompt(system="Test")
        assert prompt.name == "chat-prompt"

    def test_allows_custom_name(self) -> None:
        """Should allow setting a custom name."""
        prompt = ChatPrompt(name="my-custom-prompt", system="Test")
        assert prompt.name == "my-custom-prompt"

    def test_sets_default_model(self) -> None:
        """Should set default model to 'gpt-4o-mini'."""
        prompt = ChatPrompt(system="Test")
        assert prompt.model == "gpt-4o-mini"

    def test_allows_custom_model(self) -> None:
        """Should allow setting a custom model."""
        prompt = ChatPrompt(system="Test", model="claude-3-opus")
        assert prompt.model == "claude-3-opus"

    def test_stores_model_parameters(self) -> None:
        """Should store model_parameters as model_kwargs."""
        params = {"temperature": 0.7, "max_tokens": 100}
        prompt = ChatPrompt(system="Test", model_parameters=params)
        assert prompt.model_kwargs == params


class TestChatPromptGetMessages:
    """Tests for ChatPrompt.get_messages() method."""

    def test_returns_system_user_in_order(self) -> None:
        """Should return system and user messages in correct order."""
        prompt = ChatPrompt(
            system="System message",
            user="User message",
        )

        messages = prompt.get_messages()

        assert len(messages) == 2
        assert messages[0] == {"role": "system", "content": "System message"}
        assert messages[1] == {"role": "user", "content": "User message"}

    def test_returns_messages_list_unchanged(self) -> None:
        """Should return messages list as-is when no dataset_item."""
        original_messages = [
            {"role": "system", "content": "Be helpful."},
            {"role": "user", "content": "Hello {name}"},
        ]
        prompt = ChatPrompt(messages=original_messages)

        messages = prompt.get_messages()

        assert messages == original_messages

    def test_replaces_placeholders_with_dataset_item_values(self) -> None:
        """Should replace {key} placeholders with dataset item values."""
        prompt = ChatPrompt(
            system="You are {role}.",
            user="Answer: {question}",
        )

        messages = prompt.get_messages(
            dataset_item={"role": "a teacher", "question": "What is 2+2?"}
        )

        assert messages[0]["content"] == "You are a teacher."
        assert messages[1]["content"] == "Answer: What is 2+2?"

    def test_handles_multiple_placeholders_same_key(self) -> None:
        """Should replace all occurrences of the same placeholder."""
        prompt = ChatPrompt(user="Hello {name}, nice to meet you {name}!")

        messages = prompt.get_messages(dataset_item={"name": "Alice"})

        assert messages[0]["content"] == "Hello Alice, nice to meet you Alice!"

    def test_handles_multiple_different_placeholders(self) -> None:
        """Should replace multiple different placeholders."""
        prompt = ChatPrompt(user="{greeting} {name}, the answer is {answer}.")

        messages = prompt.get_messages(
            dataset_item={"greeting": "Hi", "name": "Bob", "answer": "42"}
        )

        assert messages[0]["content"] == "Hi Bob, the answer is 42."

    def test_preserves_unreplaced_placeholders(self) -> None:
        """Should preserve placeholders that don't match dataset item keys."""
        prompt = ChatPrompt(user="Hello {name}, your ID is {id}.")

        messages = prompt.get_messages(dataset_item={"name": "Alice"})

        assert messages[0]["content"] == "Hello Alice, your ID is {id}."

    def test_returns_deep_copy(self) -> None:
        """Should return a deep copy, not modify the original."""
        prompt = ChatPrompt(user="Hello {name}!")

        messages1 = prompt.get_messages(dataset_item={"name": "Alice"})
        messages2 = prompt.get_messages(dataset_item={"name": "Bob"})

        assert messages1[0]["content"] == "Hello Alice!"
        assert messages2[0]["content"] == "Hello Bob!"
        # Original user should be unchanged
        assert prompt.user == "Hello {name}!"


class TestChatPromptMultimodalContent:
    """Tests for multimodal content handling."""

    def test_handles_text_content_parts(self) -> None:
        """Should replace placeholders in text content parts."""
        prompt = ChatPrompt(
            messages=[
                {
                    "role": "user",
                    "content": [
                        {"type": "text", "text": "Analyze {topic}"},
                    ],
                }
            ]
        )

        messages = prompt.get_messages(dataset_item={"topic": "this image"})

        assert messages[0]["content"][0]["text"] == "Analyze this image"

    def test_handles_image_url_content_parts(self) -> None:
        """Should replace placeholders in image URLs."""
        prompt = ChatPrompt(
            messages=[
                {
                    "role": "user",
                    "content": [
                        {"type": "text", "text": "What is this?"},
                        {"type": "image_url", "image_url": {"url": "{image_url}"}},
                    ],
                }
            ]
        )

        messages = prompt.get_messages(
            dataset_item={"image_url": "https://example.com/image.png"}
        )

        assert (
            messages[0]["content"][1]["image_url"]["url"]
            == "https://example.com/image.png"
        )

    def test_has_content_parts_returns_true_for_multimodal(self) -> None:
        """_has_content_parts should return True for multimodal content."""
        prompt = ChatPrompt(
            messages=[
                {
                    "role": "user",
                    "content": [
                        {"type": "text", "text": "Hello"},
                    ],
                }
            ]
        )

        assert prompt._has_content_parts() is True

    def test_has_content_parts_returns_false_for_text_only(self) -> None:
        """_has_content_parts should return False for text-only content."""
        prompt = ChatPrompt(messages=[{"role": "user", "content": "Hello"}])

        assert prompt._has_content_parts() is False

    def test_handles_mixed_text_and_image_parts(self) -> None:
        """Should handle messages with both text and image parts."""
        prompt = ChatPrompt(
            messages=[
                {
                    "role": "user",
                    "content": [
                        {"type": "text", "text": "Question about {topic}:"},
                        {
                            "type": "image_url",
                            "image_url": {"url": "data:image/png;base64,abc"},
                        },
                        {"type": "text", "text": "What do you see?"},
                    ],
                }
            ]
        )

        messages = prompt.get_messages(dataset_item={"topic": "this diagram"})

        assert messages[0]["content"][0]["text"] == "Question about this diagram:"
        assert (
            messages[0]["content"][1]["image_url"]["url"] == "data:image/png;base64,abc"
        )
        assert messages[0]["content"][2]["text"] == "What do you see?"


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

        # Same object
        assert result is messages
        assert messages[0]["content"] == "Hello World!"


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

        # Modify copied messages
        copied.messages[0]["content"] = "Modified"

        # Original should be unchanged
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

        # Modify copied tools
        copied.tools[0]["function"]["name"] = "modified"

        # Original should be unchanged
        assert original.tools[0]["function"]["name"] == "test"

    def test_copy_deep_copies_model_kwargs(self) -> None:
        """copy should deep copy model_kwargs."""
        original = ChatPrompt(
            system="Test",
            model_parameters={"nested": {"key": "value"}},
        )
        copied = original.copy()

        # Modify copied model_kwargs
        copied.model_kwargs["nested"]["key"] = "modified"

        # Original should be unchanged
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

        assert prompt.messages[0]["content"] == "Original"


class TestChatPromptStringRepresentation:
    """Tests for __str__ and __repr__ methods."""

    def test_str_serializes_messages(self) -> None:
        """__str__ should serialize messages to JSON-like string."""
        prompt = ChatPrompt(system="Stay concise.", user="{question}")

        prompt_str = str(prompt)

        assert "Stay concise." in prompt_str
        assert "{question}" in prompt_str
        assert "ChatPrompt object at" not in prompt_str

    def test_str_truncates_long_messages(self) -> None:
        """__str__ should truncate very long messages."""
        long_user = "a" * 600
        prompt = ChatPrompt(user=long_user)

        prompt_str = str(prompt)

        assert prompt_str.endswith("...")
        # Allow for JSON structure overhead (quotes, brackets, etc.)
        assert len(prompt_str) <= ChatPrompt.DISPLAY_TRUNCATION_LENGTH + 20

    def test_repr_includes_name(self) -> None:
        """__repr__ should include the prompt name."""
        prompt = ChatPrompt(name="my-prompt", system="Test")

        repr_str = repr(prompt)

        assert "my-prompt" in repr_str
        assert "ChatPrompt" in repr_str


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


class TestChatPromptWithTools:
    """Tests for ChatPrompt with tools."""

    def test_stores_tools(self) -> None:
        """Should store tools when provided."""
        tools = [
            {
                "type": "function",
                "function": {
                    "name": "search",
                    "description": "Search the web",
                    "parameters": {
                        "type": "object",
                        "properties": {"query": {"type": "string"}},
                        "required": ["query"],
                    },
                },
            }
        ]
        prompt = ChatPrompt(system="Use tools", tools=tools)

        assert prompt.tools == tools

    def test_wraps_function_map_with_track(self) -> None:
        """Should wrap function_map functions with Opik track."""

        def my_tool(query: str) -> str:
            return f"Result for {query}"

        prompt = ChatPrompt(system="Test", function_map={"my_tool": my_tool})

        # Function should be wrapped (will have __wrapped__ or be track-wrapped)
        assert "my_tool" in prompt.function_map

    def test_preserves_already_tracked_functions(self) -> None:
        """Should preserve functions that are already tracked."""
        from opik import track

        @track(type="tool")
        def my_tracked_tool(query: str) -> str:
            return f"Result for {query}"

        prompt = ChatPrompt(system="Test", function_map={"my_tool": my_tracked_tool})

        # Should be the same function (not double-wrapped)
        assert prompt.function_map["my_tool"] is my_tracked_tool


class TestChatPromptEdgeCases:
    """Edge case tests for ChatPrompt."""

    def test_handles_empty_dataset_item(self) -> None:
        """Should handle empty dataset_item dict."""
        prompt = ChatPrompt(user="Hello {name}!")

        messages = prompt.get_messages(dataset_item={})

        assert messages[0]["content"] == "Hello {name}!"

    def test_handles_non_string_dataset_values(self) -> None:
        """Should convert non-string dataset values to strings."""
        prompt = ChatPrompt(user="Count is {count}")

        messages = prompt.get_messages(dataset_item={"count": 42})

        assert messages[0]["content"] == "Count is 42"

    def test_handles_unicode_content(self) -> None:
        """Should handle Unicode content correctly."""
        prompt = ChatPrompt(user="Hello {name}! 🌍")

        messages = prompt.get_messages(dataset_item={"name": "世界"})

        assert messages[0]["content"] == "Hello 世界! 🌍"

    def test_handles_special_characters_in_placeholders(self) -> None:
        """Should handle special regex characters in values."""
        prompt = ChatPrompt(user="Pattern: {pattern}")

        messages = prompt.get_messages(dataset_item={"pattern": "a.*b"})

        assert messages[0]["content"] == "Pattern: a.*b"

    def test_handles_curly_braces_in_values(self) -> None:
        """Should handle curly braces in replacement values."""
        prompt = ChatPrompt(user="Code: {code}")

        messages = prompt.get_messages(dataset_item={"code": "{x: 1}"})

        assert messages[0]["content"] == "Code: {x: 1}"
