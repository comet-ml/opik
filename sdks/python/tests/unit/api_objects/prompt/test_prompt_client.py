"""Unit tests for PromptClient to verify API endpoint selection logic."""

from unittest import mock
from typing import Optional

import pytest

from opik.api_objects.prompt import client as prompt_client
from opik.api_objects.prompt import types as prompt_types
from opik.rest_api import core as rest_api_core
from opik.rest_api.types import prompt_version_detail


@pytest.fixture
def mock_rest_client():
    """Create a mock REST client."""
    client = mock.Mock()
    client.prompts = mock.Mock()
    return client


@pytest.fixture
def client(mock_rest_client):
    """Create a PromptClient with a mock REST client."""
    return prompt_client.PromptClient(mock_rest_client)


def _make_mock_version(
    template: str = "test template",
    version_type: str = "mustache",
    metadata: Optional[dict] = None,
) -> prompt_version_detail.PromptVersionDetail:
    """Helper to create a mock PromptVersionDetail."""
    return prompt_version_detail.PromptVersionDetail(
        id="version-id",
        prompt_id="prompt-id",
        template=template,
        type=version_type,
        metadata=metadata,
        commit="abc123",
        template_structure="text",
    )


def _make_404_error() -> rest_api_core.ApiError:
    """Helper to create a 404 ApiError."""
    error = rest_api_core.ApiError(status_code=404, body=None)
    return error


class TestPromptClientEndpointSelection:
    """Tests to verify that PromptClient calls the correct REST endpoint based on parameters."""

    def test_create_new_prompt_with_container_params_calls_create_prompt(
        self, client, mock_rest_client
    ):
        """When creating a new prompt with id/description/tags, should call create_prompt endpoint."""
        mock_rest_client.prompts.retrieve_prompt_version.side_effect = [
            _make_404_error(),
            _make_404_error(),
            _make_mock_version(),
        ]

        client.create_prompt(
            name="test-prompt",
            prompt="test template",
            metadata=None,
            type=prompt_types.PromptType.MUSTACHE,
            id="custom-id",
            description="A test prompt",
            tags=["test", "unit"],
        )

        mock_rest_client.prompts.create_prompt.assert_called_once()
        mock_rest_client.prompts.create_prompt_version.assert_not_called()

        call_kwargs = mock_rest_client.prompts.create_prompt.call_args[1]
        assert call_kwargs["name"] == "test-prompt"
        assert call_kwargs["id"] == "custom-id"
        assert call_kwargs["description"] == "A test prompt"
        assert call_kwargs["tags"] == ["test", "unit"]

    def test_create_new_prompt_without_container_params_calls_create_version(
        self, client, mock_rest_client
    ):
        """When creating a new prompt without id/description/tags, should call create_prompt_version."""
        mock_rest_client.prompts.retrieve_prompt_version.side_effect = _make_404_error()
        mock_rest_client.prompts.create_prompt_version.return_value = (
            _make_mock_version()
        )

        client.create_prompt(
            name="test-prompt",
            prompt="test template",
            metadata=None,
            type=prompt_types.PromptType.MUSTACHE,
        )

        mock_rest_client.prompts.create_prompt_version.assert_called_once()
        mock_rest_client.prompts.create_prompt.assert_not_called()

    def test_update_existing_prompt_always_calls_create_version(
        self, client, mock_rest_client
    ):
        """When updating an existing prompt, should always call create_prompt_version regardless of params."""
        existing_version = _make_mock_version(template="old template")
        mock_rest_client.prompts.retrieve_prompt_version.return_value = existing_version
        mock_rest_client.prompts.create_prompt_version.return_value = (
            _make_mock_version(template="new template")
        )

        client.create_prompt(
            name="test-prompt",
            prompt="new template",
            metadata=None,
            type=prompt_types.PromptType.MUSTACHE,
            id="custom-id",
            description="Updated description",
            tags=["updated"],
        )

        mock_rest_client.prompts.create_prompt_version.assert_called_once()
        mock_rest_client.prompts.create_prompt.assert_not_called()

    def test_create_new_prompt_with_only_id_calls_create_prompt(
        self, client, mock_rest_client
    ):
        """When creating a new prompt with only id parameter, should call create_prompt."""
        mock_rest_client.prompts.retrieve_prompt_version.side_effect = [
            _make_404_error(),
            _make_404_error(),
            _make_mock_version(),
        ]

        client.create_prompt(
            name="test-prompt",
            prompt="test template",
            metadata=None,
            type=prompt_types.PromptType.MUSTACHE,
            id="custom-id",
        )

        mock_rest_client.prompts.create_prompt.assert_called_once()
        mock_rest_client.prompts.create_prompt_version.assert_not_called()

    def test_create_new_prompt_with_only_description_calls_create_prompt(
        self, client, mock_rest_client
    ):
        """When creating a new prompt with only description parameter, should call create_prompt."""
        mock_rest_client.prompts.retrieve_prompt_version.side_effect = [
            _make_404_error(),
            _make_404_error(),
            _make_mock_version(),
        ]

        client.create_prompt(
            name="test-prompt",
            prompt="test template",
            metadata=None,
            type=prompt_types.PromptType.MUSTACHE,
            description="A test prompt",
        )

        mock_rest_client.prompts.create_prompt.assert_called_once()
        mock_rest_client.prompts.create_prompt_version.assert_not_called()

    def test_create_new_prompt_with_only_tags_calls_create_prompt(
        self, client, mock_rest_client
    ):
        """When creating a new prompt with only tags parameter, should call create_prompt."""
        mock_rest_client.prompts.retrieve_prompt_version.side_effect = [
            _make_404_error(),
            _make_404_error(),
            _make_mock_version(),
        ]

        client.create_prompt(
            name="test-prompt",
            prompt="test template",
            metadata=None,
            type=prompt_types.PromptType.MUSTACHE,
            tags=["test"],
        )

        mock_rest_client.prompts.create_prompt.assert_called_once()
        mock_rest_client.prompts.create_prompt_version.assert_not_called()
