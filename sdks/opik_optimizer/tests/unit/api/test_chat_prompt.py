import pytest

from opik_optimizer.api_objects.chat_prompt import ChatPrompt


def test_chat_prompt_str_serializes_messages() -> None:
    prompt = ChatPrompt(system="Stay concise.", user="{question}")

    prompt_str = str(prompt)

    assert "Stay concise." in prompt_str
    assert "{question}" in prompt_str
    assert "ChatPrompt object at" not in prompt_str
    assert "chat-prompt" in repr(prompt)


def test_chat_prompt_str_truncates_long_messages() -> None:
    long_user = "a" * 600
    prompt = ChatPrompt(user=long_user)

    prompt_str = str(prompt)

    assert prompt_str.endswith("...")
    # Allow for JSON structure overhead (quotes, brackets, etc.)
    assert len(prompt_str) <= ChatPrompt.DISPLAY_TRUNCATION_LENGTH + 20


def test_chat_prompt_rejects_empty_text_message() -> None:
    with pytest.raises(ValueError):
        ChatPrompt(messages=[{"role": "user", "content": "   "}])


def test_chat_prompt_rejects_empty_multimodal_message() -> None:
    with pytest.raises(ValueError):
        ChatPrompt(messages=[{"role": "user", "content": []}])

    with pytest.raises(ValueError):
        ChatPrompt(
            messages=[
                {
                    "role": "user",
                    "content": [{"type": "text", "text": "   \n"}],
                }
            ]
        )

    with pytest.raises(ValueError):
        ChatPrompt(
            messages=[
                {
                    "role": "user",
                    "content": [
                        {
                            "type": "image_url",
                            "image_url": {"url": "   ", "detail": "high"},
                        }
                    ],
                }
            ]
        )


def test_chat_prompt_accepts_mcp_stdio_tool() -> None:
    prompt = ChatPrompt(
        system="System",
        tools=[
            {
                "type": "mcp",
                "server_label": "context7",
                "command": "npx",
                "args": [],
                "env": {},
                "allowed_tools": ["get-library-docs"],
            }
        ],
    )

    assert prompt.tools is not None


def test_chat_prompt_accepts_mcp_remote_tool() -> None:
    prompt = ChatPrompt(
        system="System",
        tools=[
            {
                "type": "mcp",
                "server_label": "remote-docs",
                "server_url": "https://mcp.example.com",
                "allowed_tools": ["search-docs"],
            }
        ],
    )

    assert prompt.tools is not None


def test_chat_prompt_rejects_remote_mcp_without_url() -> None:
    with pytest.raises(ValueError):
        ChatPrompt(
            system="System",
            tools=[
                {
                    "type": "mcp",
                    "server_label": "remote-docs",
                }
            ],
        )


def test_chat_prompt_get_messages_with_content_parts() -> None:
    prompt = ChatPrompt(
        messages=[
            {
                "role": "user",
                "content": [
                    {"type": "text", "text": "Question about {topic}:"},
                    {
                        "type": "image_url",
                        "image_url": {"url": "{image_url}", "detail": "high"},
                    },
                    {"type": "text", "text": "What do you see?"},
                ],
            }
        ]
    )

    messages = prompt.get_messages(
        dataset_item={"topic": "this diagram", "image_url": "data:image/png;base64,abc"}
    )

    assert messages[0]["content"][0]["text"] == "Question about this diagram:"
    assert messages[0]["content"][1]["image_url"]["url"] == "data:image/png;base64,abc"
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
        assert copied.messages is not None
        copied.messages[0]["content"] = "Modified"

        # Original should be unchanged
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

        # Modify copied tools
        assert copied.tools is not None
        copied.tools[0]["function"]["name"] = "modified"

        # Original should be unchanged
        assert original.tools is not None
        assert original.tools[0]["function"]["name"] == "test"

    def test_copy_deep_copies_model_kwargs(self) -> None:
        """copy should deep copy model_kwargs."""
        original = ChatPrompt(
            system="Test",
            model_parameters={"nested": {"key": "value"}},
        )
        copied = original.copy()

        # Modify copied model_kwargs
        assert copied.model_kwargs is not None
        copied.model_kwargs["nested"]["key"] = "modified"

        # Original should be unchanged
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
