import json
import pytest
from unittest import mock

from opik.api_objects.prompt import ChatPrompt, PromptType
from opik.rest_api import core as rest_api_core


class TestChatPrompt:
    """Unit tests for ChatPrompt class."""

    @pytest.fixture
    def mock_opik_client(self):
        """Mock the Opik client to avoid actual API calls."""
        with mock.patch(
            "opik.api_objects.opik_client.get_client_cached"
        ) as mock_client:
            mock_rest_client = mock.MagicMock()
            mock_client.return_value.rest_client = mock_rest_client

            # Mock the create_prompt response
            mock_response = mock.MagicMock()
            mock_response.commit = "abc123"
            mock_response.prompt_id = "prompt-id-123"
            mock_response.id = "version-id-456"

            mock_rest_client.prompts.create_prompt_version.return_value = mock_response
            # Raise 404 ApiError to simulate prompt not found
            not_found_error = rest_api_core.ApiError(status_code=404, body="Not found")
            mock_rest_client.prompts.retrieve_prompt_version.side_effect = (
                not_found_error
            )

            yield mock_rest_client

    def test_chat_prompt__init__happyflow(self, mock_opik_client):
        """Test ChatPrompt initialization with basic messages."""
        messages = [
            {"role": "system", "content": "You are helpful."},
            {"role": "user", "content": "Hello!"},
        ]

        chat_prompt = ChatPrompt(
            name="test-prompt",
            messages=messages,
        )

        assert chat_prompt.name == "test-prompt"
        assert chat_prompt.messages == messages
        assert chat_prompt.type == PromptType.MUSTACHE
        assert chat_prompt.commit == "abc123"
        assert chat_prompt.__internal_api__prompt_id__ == "prompt-id-123"
        assert chat_prompt.__internal_api__version_id__ == "version-id-456"

    def test_chat_prompt__format__simple_variables__happyflow(self, mock_opik_client):
        """Test formatting chat messages with simple string variables."""
        messages = [
            {"role": "system", "content": "You are a {{role}}."},
            {"role": "user", "content": "Hello {{name}}!"},
        ]

        chat_prompt = ChatPrompt(
            name="test-prompt",
            messages=messages,
        )

        formatted = chat_prompt.format(variables={"role": "assistant", "name": "Alice"})

        assert len(formatted) == 2
        assert formatted[0]["role"] == "system"
        assert formatted[0]["content"] == "You are a assistant."
        assert formatted[1]["role"] == "user"
        assert formatted[1]["content"] == "Hello Alice!"

    def test_chat_prompt__format__multiple_variables_in_one_message(
        self, mock_opik_client
    ):
        """Test formatting with multiple variables in a single message."""
        messages = [
            {
                "role": "user",
                "content": "My name is {{name}} and I live in {{city}}, {{country}}.",
            },
        ]

        chat_prompt = ChatPrompt(
            name="test-prompt",
            messages=messages,
        )

        formatted = chat_prompt.format(
            variables={"name": "Bob", "city": "Paris", "country": "France"}
        )

        assert formatted[0]["content"] == "My name is Bob and I live in Paris, France."

    def test_chat_prompt__messages_property__returns_copy(self, mock_opik_client):
        """Test that messages property returns a deep copy."""
        messages = [
            {"role": "user", "content": "Test"},
        ]

        chat_prompt = ChatPrompt(
            name="test-prompt",
            messages=messages,
        )

        # Get messages and modify
        retrieved_messages = chat_prompt.messages
        retrieved_messages[0]["content"] = "Modified"

        # Original should be unchanged
        assert chat_prompt.messages[0]["content"] == "Test"

    def test_chat_prompt__metadata_property__returns_copy(self, mock_opik_client):
        """Test that metadata property returns a deep copy."""
        metadata = {"key": "value"}

        chat_prompt = ChatPrompt(
            name="test-prompt",
            messages=[{"role": "user", "content": "Test"}],
            metadata=metadata,
        )

        # Get metadata and modify
        retrieved_metadata = chat_prompt.metadata
        retrieved_metadata["key"] = "modified"

        # Original should be unchanged
        assert chat_prompt.metadata["key"] == "value"

    def test_chat_prompt__with_metadata__happyflow(self, mock_opik_client):
        """Test ChatPrompt with metadata."""
        messages = [{"role": "user", "content": "Test"}]
        metadata = {"version": "1.0", "author": "test"}

        chat_prompt = ChatPrompt(
            name="test-prompt",
            messages=messages,
            metadata=metadata,
        )

        assert chat_prompt.metadata == metadata

    def test_chat_prompt__type_jinja2__happyflow(self, mock_opik_client):
        """Test ChatPrompt with Jinja2 template type."""
        messages = [
            {"role": "user", "content": "Hello {{ name }}!"},
        ]

        chat_prompt = ChatPrompt(
            name="test-prompt",
            messages=messages,
            type=PromptType.JINJA2,
        )

        formatted = chat_prompt.format(variables={"name": "Dave"})
        assert formatted[0]["content"] == "Hello Dave!"

    def test_chat_prompt__empty_messages__happyflow(self, mock_opik_client):
        """Test ChatPrompt with empty messages list."""
        chat_prompt = ChatPrompt(
            name="test-prompt",
            messages=[],
        )

        assert chat_prompt.messages == []
        formatted = chat_prompt.format(variables={})
        assert formatted == []

    def test_chat_prompt__multimodal_content__preserves_structure(
        self, mock_opik_client
    ):
        """Test that multimodal content structure is preserved."""
        messages = [
            {
                "role": "user",
                "content": [
                    {"type": "text", "text": "What's in this image?"},
                    {"type": "image_url", "image_url": {"url": "test.jpg"}},
                ],
            },
        ]

        chat_prompt = ChatPrompt(
            name="test-prompt",
            messages=messages,
        )

        assert chat_prompt.messages == messages
        assert isinstance(chat_prompt.messages[0]["content"], list)
        assert len(chat_prompt.messages[0]["content"]) == 2

    def test_chat_prompt__sync_with_backend__sends_json_string(self, mock_opik_client):
        """Test that _sync_with_backend sends messages as JSON string."""
        messages = [
            {"role": "system", "content": "Test"},
            {"role": "user", "content": "Hello"},
        ]

        chat_prompt = ChatPrompt(
            name="test-prompt",
            messages=messages,
        )

        # Check that create_prompt_version was called with JSON string
        mock_opik_client.prompts.create_prompt_version.assert_called_once()
        call_args = mock_opik_client.prompts.create_prompt_version.call_args

        # The version object should have template as JSON string
        version_arg = call_args.kwargs["version"]
        assert version_arg.template == json.dumps(messages)
        assert version_arg.template_structure == "chat"

    def test_chat_prompt__from_fern_prompt_version__happyflow(self, mock_opik_client):
        """Test creating ChatPrompt from Fern prompt version."""
        messages = [
            {"role": "system", "content": "Test"},
            {"role": "user", "content": "Hello {{name}}"},
        ]

        mock_version = mock.MagicMock()
        mock_version.id = "version-id"
        mock_version.prompt_id = "prompt-id"
        mock_version.template = json.dumps(messages)
        mock_version.commit = "abc123"
        mock_version.metadata = {"key": "value"}
        mock_version.type = "mustache"

        chat_prompt = ChatPrompt.from_fern_prompt_version(
            name="test-prompt",
            prompt_version=mock_version,
        )

        assert chat_prompt.name == "test-prompt"
        assert chat_prompt.messages == messages
        assert chat_prompt.commit == "abc123"
        assert chat_prompt.metadata == {"key": "value"}
        assert chat_prompt.__internal_api__version_id__ == "version-id"
        assert chat_prompt.__internal_api__prompt_id__ == "prompt-id"

    def test_chat_prompt__format_with_multimodal_content__happyflow(
        self, mock_opik_client
    ):
        """Test formatting with multimodal content."""
        messages = [
            {
                "role": "user",
                "content": [
                    {"type": "text", "text": "Test {{var}}"},
                    {"type": "image_url", "image_url": {"url": "test.jpg"}},
                ],
            },
        ]

        chat_prompt = ChatPrompt(
            name="test-prompt",
            messages=messages,
        )

        # Format with variables
        formatted = chat_prompt.format(variables={"var": "value"})

        assert len(formatted) == 1
        assert "content" in formatted[0]


def test_to_info_dict__chat_prompt__happyflow():
    """Test to_info_dict for ChatPrompt."""
    with mock.patch("opik.api_objects.opik_client.get_client_cached"):
        messages = [{"role": "user", "content": "Test"}]

        # Create a minimal mock for the API response
        with mock.patch(
            "opik.api_objects.prompt.client.PromptClient.create_prompt"
        ) as mock_create:
            mock_response = mock.MagicMock()
            mock_response.commit = "abc123"
            mock_response.prompt_id = "prompt-id"
            mock_response.id = "version-id"
            mock_create.return_value = mock_response

            chat_prompt = ChatPrompt(
                name="test-prompt",
                messages=messages,
            )

    from opik.api_objects.prompt.chat_prompt import to_info_dict

    info = to_info_dict(chat_prompt)

    assert info["name"] == "test-prompt"
    assert info["version"]["messages"] == messages
    assert info["version"]["template_structure"] == "chat"
    assert info["id"] == "prompt-id"
    assert info["version"]["commit"] == "abc123"
    assert info["version"]["id"] == "version-id"
