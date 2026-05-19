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
    version_number: Optional[str] = "v1",
    tags: Optional[list] = None,
) -> prompt_version_detail.PromptVersionDetail:
    """Helper to create a mock PromptVersionDetail."""
    return prompt_version_detail.PromptVersionDetail(
        id="version-id",
        prompt_id="prompt-id",
        template=template,
        type=version_type,
        metadata=metadata,
        commit="abc123",
        version_number=version_number,
        tags=tags,
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
        """When creating a new prompt with id/description/tags, should call create_prompt endpoint.
        Also asserts that version_number survives the tag-rebuild branch."""
        mock_rest_client.prompts.retrieve_prompt_version.side_effect = [
            _make_404_error(),
            _make_mock_version(tags=None),
        ]

        result = client.create_prompt(
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
        assert result.version_number == "v1"
        assert result.tags == ["test", "unit"]

    def test_create_new_prompt_without_container_params_calls_create_version(
        self, client, mock_rest_client
    ):
        """When creating a new prompt without id/description/tags, should call create_prompt_version.
        Also asserts that version_number from the create response is surfaced."""
        mock_rest_client.prompts.retrieve_prompt_version.side_effect = _make_404_error()
        mock_rest_client.prompts.create_prompt_version.return_value = (
            _make_mock_version()
        )

        result = client.create_prompt(
            name="test-prompt",
            prompt="test template",
            metadata=None,
            type=prompt_types.PromptType.MUSTACHE,
        )

        mock_rest_client.prompts.create_prompt_version.assert_called_once()
        mock_rest_client.prompts.create_prompt.assert_not_called()
        assert result.version_number == "v1"

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
        """When creating a new prompt with only tags parameter, should call create_prompt.
        Tags-only takes the rebuild branch — assert version_number survives it
        (regression for the bug where tag injection dropped version_number)."""
        mock_rest_client.prompts.retrieve_prompt_version.side_effect = [
            _make_404_error(),
            _make_mock_version(tags=None),
        ]

        result = client.create_prompt(
            name="test-prompt",
            prompt="test template",
            metadata=None,
            type=prompt_types.PromptType.MUSTACHE,
            tags=["test"],
        )

        mock_rest_client.prompts.create_prompt.assert_called_once()
        mock_rest_client.prompts.create_prompt_version.assert_not_called()
        assert result.version_number == "v1"
        assert result.tags == ["test"]


class TestInternalCreateMask:
    """Tests for __internal__create_mask method."""

    def test_create_mask_calls_create_prompt_version_with_mask_type(
        self, client, mock_rest_client
    ):
        expected = _make_mock_version()
        mock_rest_client.prompts.create_prompt_version.return_value = expected

        result = client._PromptClient__internal_api__create_mask(
            name="test-prompt",
            prompt="masked template",
        )

        mock_rest_client.prompts.create_prompt_version.assert_called_once()
        call_kwargs = mock_rest_client.prompts.create_prompt_version.call_args[1]
        assert call_kwargs["name"] == "test-prompt"
        assert call_kwargs["version"].template == "masked template"
        assert call_kwargs["version"].version_type == "mask"
        assert result == expected

    def test_create_mask_passes_all_parameters(self, client, mock_rest_client):
        mock_rest_client.prompts.create_prompt_version.return_value = (
            _make_mock_version()
        )

        client._PromptClient__internal_api__create_mask(
            name="test-prompt",
            prompt="masked template",
            type=prompt_types.PromptType.JINJA2,
            metadata={"key": "value"},
            template_structure="chat",
            project_name="my-project",
            change_description="mask for testing",
        )

        call_kwargs = mock_rest_client.prompts.create_prompt_version.call_args[1]
        assert call_kwargs["name"] == "test-prompt"
        assert call_kwargs["template_structure"] == "chat"
        assert call_kwargs["project_name"] == "my-project"
        version = call_kwargs["version"]
        assert version.template == "masked template"
        assert version.version_type == "mask"
        assert version.type == prompt_types.PromptType.JINJA2
        assert version.metadata == {"key": "value"}
        assert version.change_description == "mask for testing"

    def test_create_mask_defaults(self, client, mock_rest_client):
        mock_rest_client.prompts.create_prompt_version.return_value = (
            _make_mock_version()
        )

        client._PromptClient__internal_api__create_mask(
            name="test-prompt",
            prompt="template",
        )

        call_kwargs = mock_rest_client.prompts.create_prompt_version.call_args[1]
        assert call_kwargs["template_structure"] == "text"
        assert call_kwargs["project_name"] is None
        version = call_kwargs["version"]
        assert version.type == prompt_types.PromptType.MUSTACHE
        assert version.metadata is None
        assert version.change_description is None


class TestGetPromptByVersionSelector:
    """Tests for the new sequential ``version`` parameter (e.g. ``"v3"``)."""

    @pytest.fixture(autouse=True)
    def clear_global_cache(self):
        yield
        prompt_cache.get_global_cache().clear()

    def test_get_prompt__version_arg__threads_through_as_version_number(
        self, client, mock_rest_client
    ):
        mock_rest_client.prompts.retrieve_prompt_version.return_value = (
            _make_mock_version()
        )

        client.get_prompt(name="my-prompt", version="v3")

        mock_rest_client.prompts.retrieve_prompt_version.assert_called_once()
        call_kwargs = mock_rest_client.prompts.retrieve_prompt_version.call_args[1]
        assert call_kwargs["name"] == "my-prompt"
        assert call_kwargs["version_number"] == "v3"
        assert call_kwargs["commit"] is None

    def test_get_prompt__commit_and_version_both_set__raises_value_error(
        self, client, mock_rest_client
    ):
        with pytest.raises(ValueError, match=r"Provide either `commit` or `version`"):
            client.get_prompt(name="my-prompt", commit="abc12345", version="v1")
        mock_rest_client.prompts.retrieve_prompt_version.assert_not_called()

    def test_get_prompt_with_cache__commit_and_version_both_set__raises_value_error(
        self, client, mock_rest_client
    ):
        from opik.api_objects.prompt.text import prompt as text_prompt_module

        with pytest.raises(ValueError, match=r"Provide either `commit` or `version`"):
            client.get_prompt_with_cache(
                name="my-prompt",
                commit="abc12345",
                project_name=None,
                template_structure="text",
                prompt_cls=text_prompt_module.Prompt,
                version="v1",
            )

    def test_get_prompt_with_cache__different_versions__do_not_collide_in_cache(
        self, client, mock_rest_client
    ):
        from opik.api_objects.prompt.text import prompt as text_prompt_module

        v1 = _make_mock_version(template="content for v1")
        v2 = _make_mock_version(template="content for v2")
        mock_rest_client.prompts.retrieve_prompt_version.side_effect = [v1, v2]

        first = client.get_prompt_with_cache(
            name="my-prompt",
            commit=None,
            project_name=None,
            template_structure="text",
            prompt_cls=text_prompt_module.Prompt,
            version="v1",
        )
        second = client.get_prompt_with_cache(
            name="my-prompt",
            commit=None,
            project_name=None,
            template_structure="text",
            prompt_cls=text_prompt_module.Prompt,
            version="v2",
        )

        assert first is not None and first.prompt == "content for v1"
        assert second is not None and second.prompt == "content for v2"
        assert mock_rest_client.prompts.retrieve_prompt_version.call_count == 2

    def test_get_prompt_with_cache__commit_and_version_pin__do_not_collide(
        self, client, mock_rest_client
    ):
        from opik.api_objects.prompt.text import prompt as text_prompt_module

        by_commit = _make_mock_version(template="content by commit")
        by_version = _make_mock_version(template="content by version")
        mock_rest_client.prompts.retrieve_prompt_version.side_effect = [
            by_commit,
            by_version,
        ]

        commit_result = client.get_prompt_with_cache(
            name="my-prompt",
            commit="abc12345",
            project_name=None,
            template_structure="text",
            prompt_cls=text_prompt_module.Prompt,
        )
        version_result = client.get_prompt_with_cache(
            name="my-prompt",
            commit=None,
            project_name=None,
            template_structure="text",
            prompt_cls=text_prompt_module.Prompt,
            version="v3",
        )

        assert commit_result is not None
        assert commit_result.prompt == "content by commit"
        assert version_result is not None
        assert version_result.prompt == "content by version"
        assert mock_rest_client.prompts.retrieve_prompt_version.call_count == 2

    def test_get_prompt_with_cache__same_version_twice__second_hits_cache(
        self, client, mock_rest_client
    ):
        from opik.api_objects.prompt.text import prompt as text_prompt_module

        v2 = _make_mock_version(template="v2 content")
        mock_rest_client.prompts.retrieve_prompt_version.return_value = v2

        first = client.get_prompt_with_cache(
            name="my-prompt",
            commit=None,
            project_name=None,
            template_structure="text",
            prompt_cls=text_prompt_module.Prompt,
            version="v2",
        )
        second = client.get_prompt_with_cache(
            name="my-prompt",
            commit=None,
            project_name=None,
            template_structure="text",
            prompt_cls=text_prompt_module.Prompt,
            version="v2",
        )

        assert first is second
        assert mock_rest_client.prompts.retrieve_prompt_version.call_count == 1


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

    def test_set_environment__no_version_id__raises_value_error(self):
        from opik.api_objects.prompt.text.prompt import Prompt

        with (
            mock.patch(
                "opik.api_objects.opik_client.get_client_cached",
                return_value=mock.MagicMock(),
            ),
            mock.patch(
                "opik.api_objects.prompt.client.PromptClient.create_prompt",
                side_effect=rest_api_core.ApiError(status_code=500),
            ),
        ):
            prompt = Prompt(name="env-prompt", prompt="hello")

        assert prompt.synced is False
        assert prompt.version_id is None

        with pytest.raises(ValueError, match="has not been synced"):
            prompt.set_environment("staging")

    def test_set_environment__calls_rest_client_and_updates_local_state(self):
        from opik.api_objects.prompt.text.prompt import Prompt

        rest_client_mock = mock.MagicMock()

        with (
            mock.patch(
                "opik.api_objects.opik_client.get_global_client",
                return_value=mock.MagicMock(rest_client=rest_client_mock),
            ),
            mock.patch(
                "opik.api_objects.opik_client.get_client_cached",
                return_value=mock.MagicMock(),
            ),
            mock.patch(
                "opik.api_objects.prompt.client.PromptClient.create_prompt",
                return_value=_make_mock_version(),
            ),
        ):
            prompt = Prompt(name="env-prompt", prompt="hello")
            prompt.set_environment("staging")

        rest_client_mock.prompts.set_prompt_version_environment.assert_called_once_with(
            version_id="version-id",
            environment="staging",
        )
        assert prompt.environment == "staging"

        with (
            mock.patch(
                "opik.api_objects.opik_client.get_global_client",
                return_value=mock.MagicMock(rest_client=rest_client_mock),
            ),
        ):
            prompt.set_environment(None)
        assert prompt.environment is None
