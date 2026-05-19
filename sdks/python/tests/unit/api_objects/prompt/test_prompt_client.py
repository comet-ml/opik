"""Unit tests for PromptClient to verify API endpoint selection logic."""

from unittest import mock
from typing import Optional

import pytest

from opik.api_objects import opik_client as opik_client_module
from opik.api_objects.prompt import client as prompt_client
from opik.api_objects.prompt import prompt_cache
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


class TestGetPromptWithCacheBypass:
    """Tests for no_cache parameter in Opik.get_prompt()."""

    @pytest.fixture(autouse=True)
    def clear_global_cache(self):
        yield
        prompt_cache.get_global_cache().clear()

    @pytest.fixture
    def opik_client(self, mock_rest_client):
        client = opik_client_module.Opik()
        client._rest_client = mock_rest_client
        return client

    @pytest.mark.parametrize(
        "no_cache, expected_extra_calls",
        [
            (False, 0),
            (True, 1),
        ],
        ids=["no_cache_false__uses_cache", "no_cache_true__hits_backend"],
    )
    def test_get_prompt__second_call_after_cache_warm__respects_no_cache(
        self, opik_client, mock_rest_client, no_cache, expected_extra_calls
    ):
        version = _make_mock_version()
        mock_rest_client.prompts.retrieve_prompt_version.return_value = version

        opik_client.get_prompt(name="my-prompt", commit=None, project_name=None)
        call_count_after_warm = (
            mock_rest_client.prompts.retrieve_prompt_version.call_count
        )

        opik_client.get_prompt(
            name="my-prompt", commit=None, project_name=None, no_cache=no_cache
        )

        assert (
            mock_rest_client.prompts.retrieve_prompt_version.call_count
            == call_count_after_warm + expected_extra_calls
        )

    def test_get_prompt__no_cache_true__returns_fresh_value_from_backend(
        self, opik_client, mock_rest_client
    ):
        old_version = _make_mock_version(template="old template")
        new_version = _make_mock_version(template="new template")
        mock_rest_client.prompts.retrieve_prompt_version.side_effect = [
            old_version,
            new_version,
        ]

        opik_client.get_prompt(name="my-prompt", commit=None, project_name=None)

        result = opik_client.get_prompt(
            name="my-prompt", commit=None, project_name=None, no_cache=True
        )

        assert result is not None
        assert result.prompt == "new template"

    def test_get_prompt__no_cache_true__backend_returns_none__returns_none(
        self, opik_client, mock_rest_client
    ):
        mock_rest_client.prompts.retrieve_prompt_version.side_effect = _make_404_error()

        result = opik_client.get_prompt(
            name="missing-prompt", commit=None, project_name=None, no_cache=True
        )

        assert result is None


class TestPromptEnvironment:
    """Unit tests for the prompt ``environment`` plumbing."""

    @pytest.fixture(autouse=True)
    def clear_global_cache(self):
        yield
        prompt_cache.get_global_cache().clear()

    def test_create_new_prompt_without_container_params__forwards_environment_on_version_body(
        self, client, mock_rest_client
    ):
        mock_rest_client.prompts.retrieve_prompt_version.side_effect = _make_404_error()
        mock_rest_client.prompts.create_prompt_version.return_value = (
            _make_mock_version()
        )

        client.create_prompt(
            name="env-prompt",
            prompt="hello",
            metadata=None,
            type=prompt_types.PromptType.MUSTACHE,
            environment="staging",
        )

        mock_rest_client.prompts.create_prompt_version.assert_called_once()
        call_kwargs = mock_rest_client.prompts.create_prompt_version.call_args[1]
        assert call_kwargs["version"].environment == "staging"
        mock_rest_client.prompts.set_prompt_version_environment.assert_not_called()

    def test_create_new_prompt_with_container_params__calls_set_environment_after_create(
        self, client, mock_rest_client
    ):
        mock_rest_client.prompts.retrieve_prompt_version.side_effect = [
            _make_404_error(),
            _make_mock_version(),
        ]

        client.create_prompt(
            name="env-prompt",
            prompt="hello",
            metadata=None,
            type=prompt_types.PromptType.MUSTACHE,
            tags=["t1"],
            environment="staging",
        )

        mock_rest_client.prompts.create_prompt.assert_called_once()
        create_kwargs = mock_rest_client.prompts.create_prompt.call_args[1]
        assert "environment" not in create_kwargs
        mock_rest_client.prompts.set_prompt_version_environment.assert_called_once_with(
            version_id="version-id",
            environment="staging",
        )

    def test_update_existing_prompt__environment_differs__set_environment_called(
        self, client, mock_rest_client
    ):
        existing_version = _make_mock_version(template="same template")
        existing_version_with_env_none = prompt_version_detail.PromptVersionDetail(
            id=existing_version.id,
            prompt_id=existing_version.prompt_id,
            commit=existing_version.commit,
            template=existing_version.template,
            metadata=existing_version.metadata,
            type=existing_version.type,
            template_structure="text",
            environment=None,
        )
        mock_rest_client.prompts.retrieve_prompt_version.return_value = (
            existing_version_with_env_none
        )

        client.create_prompt(
            name="env-prompt",
            prompt="same template",
            metadata=None,
            type=prompt_types.PromptType.MUSTACHE,
            environment="production",
        )

        mock_rest_client.prompts.create_prompt_version.assert_not_called()
        mock_rest_client.prompts.create_prompt.assert_not_called()
        mock_rest_client.prompts.set_prompt_version_environment.assert_called_once_with(
            version_id="version-id",
            environment="production",
        )

    def test_update_existing_prompt__environment_unchanged__set_environment_not_called(
        self, client, mock_rest_client
    ):
        existing_version = prompt_version_detail.PromptVersionDetail(
            id="version-id",
            prompt_id="prompt-id",
            template="same template",
            type="mustache",
            metadata=None,
            commit="abc123",
            template_structure="text",
            environment="production",
        )
        mock_rest_client.prompts.retrieve_prompt_version.return_value = existing_version

        client.create_prompt(
            name="env-prompt",
            prompt="same template",
            metadata=None,
            type=prompt_types.PromptType.MUSTACHE,
            environment="production",
        )

        mock_rest_client.prompts.set_prompt_version_environment.assert_not_called()

    def test_get_prompt__forwards_environment_to_retrieve(
        self, client, mock_rest_client
    ):
        mock_rest_client.prompts.retrieve_prompt_version.return_value = (
            _make_mock_version()
        )

        client.get_prompt(name="env-prompt", environment="staging")

        mock_rest_client.prompts.retrieve_prompt_version.assert_called_once()
        call_kwargs = mock_rest_client.prompts.retrieve_prompt_version.call_args[1]
        assert call_kwargs["environment"] == "staging"

    def test_get_prompt__commit_and_environment__raises_value_error(
        self, client, mock_rest_client
    ):
        with pytest.raises(ValueError, match="mutually exclusive"):
            client.get_prompt(
                name="env-prompt", commit="abc12345", environment="staging"
            )

        mock_rest_client.prompts.retrieve_prompt_version.assert_not_called()

    def test_get_prompt_with_cache__different_environments__not_cached_together(
        self, mock_rest_client
    ):
        opik_client = opik_client_module.Opik()
        opik_client._rest_client = mock_rest_client

        staging_version = prompt_version_detail.PromptVersionDetail(
            id="staging-id",
            prompt_id="prompt-id",
            template="staging template",
            type="mustache",
            metadata=None,
            commit="aaa",
            template_structure="text",
            environment="staging",
        )
        production_version = prompt_version_detail.PromptVersionDetail(
            id="prod-id",
            prompt_id="prompt-id",
            template="prod template",
            type="mustache",
            metadata=None,
            commit="bbb",
            template_structure="text",
            environment="production",
        )
        mock_rest_client.prompts.retrieve_prompt_version.side_effect = [
            staging_version,
            production_version,
        ]

        staging = opik_client.get_prompt(name="env-prompt", environment="staging")
        production = opik_client.get_prompt(name="env-prompt", environment="production")

        assert staging is not None and production is not None
        assert staging.commit == "aaa"
        assert production.commit == "bbb"
        assert mock_rest_client.prompts.retrieve_prompt_version.call_count == 2

    def test_set_prompt_environment__sets_via_latest_version(self, mock_rest_client):
        mock_rest_client.prompts.retrieve_prompt_version.return_value = (
            _make_mock_version()
        )

        opik_client = opik_client_module.Opik()
        opik_client._rest_client = mock_rest_client

        opik_client.set_prompt_environment("env-prompt", "staging")

        mock_rest_client.prompts.retrieve_prompt_version.assert_called_once()
        retrieve_kwargs = mock_rest_client.prompts.retrieve_prompt_version.call_args[1]
        assert retrieve_kwargs["name"] == "env-prompt"
        mock_rest_client.prompts.set_prompt_version_environment.assert_called_once_with(
            version_id="version-id",
            environment="staging",
        )

    def test_set_prompt_environment__none_clears(self, mock_rest_client):
        mock_rest_client.prompts.retrieve_prompt_version.return_value = (
            _make_mock_version()
        )

        opik_client = opik_client_module.Opik()
        opik_client._rest_client = mock_rest_client

        opik_client.set_prompt_environment("env-prompt", None)

        mock_rest_client.prompts.set_prompt_version_environment.assert_called_once_with(
            version_id="version-id",
            environment=None,
        )

    def test_set_prompt_environment__forwards_project_name(self, mock_rest_client):
        mock_rest_client.prompts.retrieve_prompt_version.return_value = (
            _make_mock_version()
        )

        opik_client = opik_client_module.Opik()
        opik_client._rest_client = mock_rest_client

        opik_client.set_prompt_environment(
            "env-prompt", "staging", project_name="my-project"
        )

        mock_rest_client.prompts.retrieve_prompt_version.assert_called_once()
        retrieve_kwargs = mock_rest_client.prompts.retrieve_prompt_version.call_args[1]
        assert retrieve_kwargs["name"] == "env-prompt"
        assert retrieve_kwargs["project_name"] == "my-project"

    def test_set_prompt_environment__forwards_commit(self, mock_rest_client):
        mock_rest_client.prompts.retrieve_prompt_version.return_value = (
            _make_mock_version()
        )

        opik_client = opik_client_module.Opik()
        opik_client._rest_client = mock_rest_client

        opik_client.set_prompt_environment("env-prompt", "staging", commit="abc12345")

        mock_rest_client.prompts.retrieve_prompt_version.assert_called_once()
        retrieve_kwargs = mock_rest_client.prompts.retrieve_prompt_version.call_args[1]
        assert retrieve_kwargs["name"] == "env-prompt"
        assert retrieve_kwargs["commit"] == "abc12345"
        mock_rest_client.prompts.set_prompt_version_environment.assert_called_once_with(
            version_id="version-id",
            environment="staging",
        )

    def test_set_prompt_environment__invalidates_cache(self, mock_rest_client):
        mock_rest_client.prompts.retrieve_prompt_version.return_value = (
            _make_mock_version()
        )

        opik_client = opik_client_module.Opik()
        opik_client._rest_client = mock_rest_client
        resolved_project = opik_client._resolve_project_name(None)

        cache = prompt_cache.get_global_cache()
        cache.clear()
        try:
            sentinel = mock.MagicMock()
            cache.get_or_fetch(
                key=("env-prompt", None, resolved_project, "text", "staging"),
                fetch_fn=lambda: sentinel,
                ttl_seconds=None,
            )
            cache.get_or_fetch(
                key=("other-prompt", None, resolved_project, "text", "staging"),
                fetch_fn=lambda: sentinel,
                ttl_seconds=None,
            )

            opik_client.set_prompt_environment("env-prompt", "production")

            assert (
                cache.get(("env-prompt", None, resolved_project, "text", "staging"))
                is None
            ), "stale entry for the updated prompt should be evicted"
            assert (
                cache.get(("other-prompt", None, resolved_project, "text", "staging"))
                is sentinel
            ), "entries for unrelated prompts must not be evicted"
        finally:
            cache.clear()
