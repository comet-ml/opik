"""Tests for Prompt and ChatPrompt backend sync."""

import contextlib
import functools
from unittest.mock import patch, MagicMock

import httpx
import pytest

from opik import context_storage
from opik.api_objects import opik_client
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


def _mock_opik_client_with_project(configured_project_name):
    client = MagicMock()
    client._project_name = configured_project_name
    client._resolve_project_name = functools.partial(
        opik_client.Opik._resolve_project_name, client
    )
    return patch(
        "opik.api_objects.opik_client.get_global_client",
        return_value=client,
    )


def _create_text_prompt(project_name):
    return Prompt(
        name="test-prompt", prompt="Hello {{name}}", project_name=project_name
    )


def _create_chat_prompt(project_name):
    return ChatPrompt(
        name="test-chat",
        messages=[{"role": "user", "content": "Hello {{name}}"}],
        project_name=project_name,
    )


@pytest.mark.parametrize(
    "create_prompt",
    [
        pytest.param(_create_text_prompt, id="prompt"),
        pytest.param(_create_chat_prompt, id="chat_prompt"),
    ],
)
@pytest.mark.parametrize(
    "explicit_project_name, context_project_name, expected_project_name",
    [
        pytest.param(
            "explicit-project", "context-project", "explicit-project", id="explicit"
        ),
        pytest.param(None, "context-project", "context-project", id="context"),
        pytest.param(None, None, "configured-project", id="configured"),
    ],
)
def test_sync_with_backend__project_name_resolved_and_sent_to_backend(
    create_prompt,
    explicit_project_name,
    context_project_name,
    expected_project_name,
):
    with (
        _mock_opik_client_with_project("configured-project"),
        patch(
            "opik.api_objects.prompt.client.PromptClient.create_prompt",
            return_value=MagicMock(),
        ) as create_prompt_mock,
        (
            context_storage.project_context(context_project_name)
            if context_project_name
            else contextlib.nullcontext()
        ),
    ):
        prompt = create_prompt(explicit_project_name)

    assert create_prompt_mock.call_args.kwargs["project_name"] == expected_project_name
    assert prompt.project_name == expected_project_name
