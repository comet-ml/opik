from unittest import mock

import pytest

from opik.api_objects.agent_config.config import AgentConfig
from opik.api_objects.agent_config.blueprint import Blueprint
from opik.api_objects.agent_config.client import ConfigClient
from opik.rest_api.types.agent_blueprint_public import AgentBlueprintPublic
from opik.rest_api.types.agent_config_value_public import AgentConfigValuePublic


def _make_raw_blueprint(blueprint_id="bp-1", values=None, description=None):
    if values is None:
        values = [
            AgentConfigValuePublic(key="temp", type="float", value="0.6"),
            AgentConfigValuePublic(key="name", type="string", value="agent"),
        ]
    return AgentBlueprintPublic(
        id=blueprint_id, type="blueprint", values=values, description=description
    )


@pytest.fixture
def mock_config_client():
    return mock.Mock(spec=ConfigClient)


@pytest.fixture
def mock_rest_client():
    return mock.Mock()


@pytest.fixture
def agent_config(mock_config_client, mock_rest_client):
    return AgentConfig(
        project_name="my-project",
        config_client=mock_config_client,
        rest_client_=mock_rest_client,
    )


class TestAgentConfigProperties:
    def test_project_name(self, agent_config):
        assert agent_config.project_name == "my-project"


class TestAgentConfigGetBlueprint:
    def test_get_blueprint__returns_blueprint(self, agent_config, mock_config_client):
        mock_config_client.get_blueprint.return_value = _make_raw_blueprint()

        result = agent_config.get_blueprint()

        assert isinstance(result, Blueprint)
        assert result.id == "bp-1"

    def test_get_blueprint__with_env__passes_env(
        self, agent_config, mock_config_client
    ):
        mock_config_client.get_blueprint.return_value = _make_raw_blueprint()

        agent_config.get_blueprint(env="prod")

        mock_config_client.get_blueprint.assert_called_once_with(
            project_name="my-project", env="prod", mask_id=None
        )

    def test_get_blueprint__with_mask_id__passes_mask_id(
        self, agent_config, mock_config_client
    ):
        mock_config_client.get_blueprint.return_value = _make_raw_blueprint()

        agent_config.get_blueprint(mask_id="mask-1")

        mock_config_client.get_blueprint.assert_called_once_with(
            project_name="my-project", env=None, mask_id="mask-1"
        )

    def test_get_blueprint__with_field_types__resolves_values(
        self, agent_config, mock_config_client
    ):
        mock_config_client.get_blueprint.return_value = _make_raw_blueprint()

        result = agent_config.get_blueprint(field_types={"temp": float, "name": str})

        assert result["temp"] == 0.6
        assert result["name"] == "agent"

    def test_get_blueprint__without_field_types__infers_types_from_backend(
        self, agent_config, mock_config_client
    ):
        mock_config_client.get_blueprint.return_value = _make_raw_blueprint()

        result = agent_config.get_blueprint()

        assert result["temp"] == 0.6
        assert isinstance(result["temp"], float)
        assert result["name"] == "agent"
        assert isinstance(result["name"], str)

    def test_get_blueprint__not_found__returns_none(
        self, agent_config, mock_config_client
    ):
        mock_config_client.get_blueprint.return_value = None

        result = agent_config.get_blueprint()

        assert result is None


class TestAgentConfigGetBlueprintById:
    def test_get_blueprint_by_id__returns_blueprint(
        self, agent_config, mock_rest_client
    ):
        mock_rest_client.agent_configs.get_blueprint_by_id.return_value = (
            _make_raw_blueprint(blueprint_id="bp-specific")
        )

        result = agent_config.get_blueprint(id="bp-specific")

        assert isinstance(result, Blueprint)
        assert result.id == "bp-specific"
        mock_rest_client.agent_configs.get_blueprint_by_id.assert_called_once_with(
            "bp-specific", mask_id=None
        )

    def test_get_blueprint_by_id__not_found__returns_none(
        self, agent_config, mock_rest_client
    ):
        from opik.rest_api import core as rest_api_core

        mock_rest_client.agent_configs.get_blueprint_by_id.side_effect = (
            rest_api_core.ApiError(status_code=404, body="not found")
        )

        result = agent_config.get_blueprint(id="nonexistent")

        assert result is None


class TestAgentConfigCreateBlueprint:
    def test_create_blueprint__with_parameters__returns_blueprint(
        self, agent_config, mock_config_client, mock_rest_client
    ):
        mock_config_client.create_blueprint.return_value = "bp-new"
        mock_rest_client.agent_configs.get_blueprint_by_id.return_value = (
            _make_raw_blueprint(blueprint_id="bp-new")
        )

        result = agent_config.create_blueprint(
            parameters={"temp": 0.6, "name": "agent"}
        )

        assert isinstance(result, Blueprint)
        assert result.id == "bp-new"
        call_kwargs = mock_config_client.create_blueprint.call_args[1]
        assert "temp" in call_kwargs["fields_with_values"]
        assert "name" in call_kwargs["fields_with_values"]
        mock_rest_client.agent_configs.get_blueprint_by_id.assert_called_once_with(
            "bp-new"
        )

    def test_create_blueprint__with_fields_with_values__returns_blueprint(
        self, agent_config, mock_config_client, mock_rest_client
    ):
        mock_config_client.create_blueprint.return_value = "bp-new"
        mock_rest_client.agent_configs.get_blueprint_by_id.return_value = (
            _make_raw_blueprint()
        )

        result = agent_config.create_blueprint(
            fields_with_values={"temp": (float, 0.6)}
        )

        assert isinstance(result, Blueprint)
        call_kwargs = mock_config_client.create_blueprint.call_args[1]
        assert call_kwargs["fields_with_values"] == {"temp": (float, 0.6)}

    def test_create_blueprint__with_description__passes_description(
        self, agent_config, mock_config_client, mock_rest_client
    ):
        mock_config_client.create_blueprint.return_value = "bp-new"
        mock_rest_client.agent_configs.get_blueprint_by_id.return_value = (
            _make_raw_blueprint()
        )

        agent_config.create_blueprint(parameters={"temp": 0.6}, description="v1")

        call_kwargs = mock_config_client.create_blueprint.call_args[1]
        assert call_kwargs["description"] == "v1"


class TestAgentConfigCreateMask:
    def test_create_mask__with_parameters__returns_mask_id(
        self, agent_config, mock_config_client
    ):
        mock_config_client.create_mask.return_value = "mask-1"

        result = agent_config.create_mask(parameters={"temp": 0.3})

        assert result == "mask-1"

    def test_create_mask__with_description__passes_description(
        self, agent_config, mock_config_client
    ):
        mock_config_client.create_mask.return_value = "mask-2"

        agent_config.create_mask(parameters={"temp": 0.3}, description="variant-A")

        call_kwargs = mock_config_client.create_mask.call_args[1]
        assert call_kwargs["description"] == "variant-A"
