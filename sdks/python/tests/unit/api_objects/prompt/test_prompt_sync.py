"""Tests for Prompt and ChatPrompt resilience when backend sync fails."""

from unittest.mock import patch, MagicMock

import httpx
import pytest

from opik.rest_api.core import ApiError
from opik.api_objects.prompt.text.prompt import Prompt
from opik.api_objects.prompt.chat.chat_prompt import ChatPrompt


_API_ERRORS = [
    pytest.param(ApiError(status_code=500, body="Internal Server Error"), id="api_500"),
    pytest.param(ApiError(status_code=503, body="Service Unavailable"), id="api_503"),
    pytest.param(httpx.ConnectError("Connection refused"), id="connect_error"),
    pytest.param(httpx.TimeoutException("Request timed out"), id="timeout"),
]


def _mock_opik_client():
    """Patch the opik_client.get_client_cached() used inside sync_with_backend."""
    return patch(
        "opik.api_objects.opik_client.get_client_cached",
        return_value=MagicMock(),
    )


class TestPromptSyncFailure:
    """Prompt should be created locally even when sync_with_backend fails with API errors."""

    @pytest.mark.parametrize("error", _API_ERRORS)
    def test_prompt_created_despite_backend_failure(self, error):
        with (
            _mock_opik_client(),
            patch(
                "opik.api_objects.prompt.client.PromptClient.create_prompt",
                side_effect=error,
            ),
        ):
            prompt = Prompt(name="test-prompt", prompt="Hello {{name}}")

        assert prompt.name == "test-prompt"
        assert prompt.prompt == "Hello {{name}}"
        assert prompt.commit is None
        assert prompt.synced is False
        assert prompt.format(name="World") == "Hello World"

    def test_sync_returns_false_on_api_error(self):
        with (
            _mock_opik_client(),
            patch(
                "opik.api_objects.prompt.client.PromptClient.create_prompt",
                side_effect=ApiError(status_code=500),
            ),
        ):
            prompt = Prompt(name="test-prompt", prompt="Hello {{name}}")
            result = prompt.sync_with_backend()

        assert result is False
        assert prompt.synced is False

    def test_sync_returns_true_on_success(self):
        mock_version = MagicMock()
        mock_version.commit = "abc123"
        mock_version.prompt_id = "pid"
        mock_version.id = "vid"
        mock_version.change_description = None
        mock_version.tags = []

        with (
            _mock_opik_client(),
            patch(
                "opik.api_objects.prompt.client.PromptClient.create_prompt",
                return_value=mock_version,
            ),
        ):
            prompt = Prompt(name="test-prompt", prompt="Hello {{name}}")

        assert prompt.commit == "abc123"
        assert prompt.synced is True

        # Re-sync also succeeds
        with (
            _mock_opik_client(),
            patch(
                "opik.api_objects.prompt.client.PromptClient.create_prompt",
                return_value=mock_version,
            ),
        ):
            assert prompt.sync_with_backend() is True
            assert prompt.synced is True

    def test_non_api_error_propagates(self):
        with (
            _mock_opik_client(),
            patch(
                "opik.api_objects.prompt.client.PromptClient.create_prompt",
                side_effect=ValueError("bad value"),
            ),
        ):
            with pytest.raises(ValueError, match="bad value"):
                Prompt(name="test-prompt", prompt="Hello {{name}}")


class TestChatPromptSyncFailure:
    """ChatPrompt should be created locally even when sync_with_backend fails with API errors."""

    MESSAGES = [{"role": "user", "content": "Hello {{name}}"}]

    @pytest.mark.parametrize("error", _API_ERRORS)
    def test_chat_prompt_created_despite_backend_failure(self, error):
        with (
            _mock_opik_client(),
            patch(
                "opik.api_objects.prompt.client.PromptClient.create_prompt",
                side_effect=error,
            ),
        ):
            prompt = ChatPrompt(name="test-chat", messages=self.MESSAGES)

        assert prompt.name == "test-chat"
        assert prompt.template == self.MESSAGES
        assert prompt.commit is None
        assert prompt.synced is False

    def test_sync_returns_false_on_api_error(self):
        with (
            _mock_opik_client(),
            patch(
                "opik.api_objects.prompt.client.PromptClient.create_prompt",
                side_effect=ApiError(status_code=500),
            ),
        ):
            prompt = ChatPrompt(name="test-chat", messages=self.MESSAGES)
            result = prompt.sync_with_backend()

        assert result is False
        assert prompt.synced is False

    def test_sync_returns_true_on_success(self):
        mock_version = MagicMock()
        mock_version.commit = "abc123"
        mock_version.prompt_id = "pid"
        mock_version.id = "vid"
        mock_version.change_description = None
        mock_version.tags = []

        with (
            _mock_opik_client(),
            patch(
                "opik.api_objects.prompt.client.PromptClient.create_prompt",
                return_value=mock_version,
            ),
        ):
            prompt = ChatPrompt(name="test-chat", messages=self.MESSAGES)

        assert prompt.commit == "abc123"
        assert prompt.synced is True

        with (
            _mock_opik_client(),
            patch(
                "opik.api_objects.prompt.client.PromptClient.create_prompt",
                return_value=mock_version,
            ),
        ):
            assert prompt.sync_with_backend() is True
            assert prompt.synced is True

    def test_non_api_error_propagates(self):
        with (
            _mock_opik_client(),
            patch(
                "opik.api_objects.prompt.client.PromptClient.create_prompt",
                side_effect=ValueError("bad value"),
            ),
        ):
            with pytest.raises(ValueError, match="bad value"):
                ChatPrompt(name="test-chat", messages=self.MESSAGES)
