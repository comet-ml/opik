from unittest import mock

import pytest

from opik.api_objects.agent_config.config import AgentConfigManager
from opik.api_objects.agent_config.blueprint import Blueprint
from opik.rest_api import core as rest_api_core
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
def mock_rest_client():
    client = mock.Mock()
    client.agent_configs = mock.Mock()
    client.agent_configs.create_agent_config.return_value = None
    client.agent_configs.get_latest_blueprint.return_value = _make_raw_blueprint()
    client.projects.retrieve_project.return_value = mock.Mock(id="proj-default")
    return client


@pytest.fixture
def agent_config(mock_rest_client):
    return AgentConfigManager(
        project_name="my-project",
        rest_client_=mock_rest_client,
    )


class TestAgentConfigManagerProperties:
    def test_project_name(self, agent_config):
        assert agent_config.project_name == "my-project"


class TestAgentConfigManagerGetBlueprint:
    def test_get_blueprint__returns_blueprint(self, agent_config, mock_rest_client):
        mock_rest_client.projects.retrieve_project.return_value = mock.Mock(id="proj-1")
        mock_rest_client.agent_configs.get_latest_blueprint.return_value = (
            _make_raw_blueprint()
        )

        result = agent_config.get_blueprint()

        assert isinstance(result, Blueprint)
        assert result.id == "bp-1"

    def test_get_blueprint__with_env__routes_to_get_blueprint_by_env(
        self, agent_config, mock_rest_client
    ):
        mock_rest_client.projects.retrieve_project.return_value = mock.Mock(id="proj-1")
        mock_rest_client.agent_configs.get_blueprint_by_env.return_value = (
            _make_raw_blueprint()
        )

        agent_config.get_blueprint(env="prod")

        mock_rest_client.agent_configs.get_blueprint_by_env.assert_called_once_with(
            env_name="prod",
            project_id="proj-1",
            mask_id=None,
            request_options=None,
        )
        mock_rest_client.agent_configs.get_latest_blueprint.assert_not_called()

    def test_get_blueprint__with_mask_id__passes_mask_id(
        self, agent_config, mock_rest_client
    ):
        mock_rest_client.projects.retrieve_project.return_value = mock.Mock(id="proj-1")
        mock_rest_client.agent_configs.get_latest_blueprint.return_value = (
            _make_raw_blueprint()
        )

        agent_config.get_blueprint(mask_id="mask-1")

        mock_rest_client.agent_configs.get_latest_blueprint.assert_called_once_with(
            project_id="proj-1",
            mask_id="mask-1",
            request_options=None,
        )

    def test_get_blueprint__with_field_types__resolves_values(
        self, agent_config, mock_rest_client
    ):
        mock_rest_client.projects.retrieve_project.return_value = mock.Mock(id="proj-1")
        mock_rest_client.agent_configs.get_latest_blueprint.return_value = (
            _make_raw_blueprint()
        )

        result = agent_config.get_blueprint(field_types={"temp": float, "name": str})

        assert result["temp"] == 0.6
        assert result["name"] == "agent"

    def test_get_blueprint__without_field_types__infers_types_from_backend(
        self, agent_config, mock_rest_client
    ):
        mock_rest_client.projects.retrieve_project.return_value = mock.Mock(id="proj-1")
        mock_rest_client.agent_configs.get_latest_blueprint.return_value = (
            _make_raw_blueprint()
        )

        result = agent_config.get_blueprint()

        assert result["temp"] == 0.6
        assert isinstance(result["temp"], float)
        assert result["name"] == "agent"
        assert isinstance(result["name"], str)

    def test_get_blueprint__not_found__returns_none(
        self, agent_config, mock_rest_client
    ):
        mock_rest_client.projects.retrieve_project.return_value = mock.Mock(id="proj-1")
        mock_rest_client.agent_configs.get_latest_blueprint.side_effect = (
            rest_api_core.ApiError(status_code=404, body="not found")
        )

        result = agent_config.get_blueprint()

        assert result is None

    @pytest.mark.parametrize(
        "mask_id",
        ["mask-1", "mask-2", None],
        ids=["mask_1", "mask_2", "no_mask"],
    )
    def test_get_blueprint__mask_id__passed_to_backend(
        self, agent_config, mock_rest_client, mask_id
    ):
        mock_rest_client.projects.retrieve_project.return_value = mock.Mock(id="proj-1")
        mock_rest_client.agent_configs.get_latest_blueprint.return_value = (
            _make_raw_blueprint()
        )

        agent_config.get_blueprint(mask_id=mask_id)

        mock_rest_client.agent_configs.get_latest_blueprint.assert_called_once_with(
            project_id="proj-1",
            mask_id=mask_id,
            request_options=None,
        )

    def test_get_blueprint__env_with_mask_id__routes_to_get_blueprint_by_env(
        self, agent_config, mock_rest_client
    ):
        mock_rest_client.projects.retrieve_project.return_value = mock.Mock(id="proj-1")
        mock_rest_client.agent_configs.get_blueprint_by_env.return_value = (
            _make_raw_blueprint()
        )

        agent_config.get_blueprint(env="prod", mask_id="mask-1")

        mock_rest_client.agent_configs.get_blueprint_by_env.assert_called_once_with(
            env_name="prod",
            project_id="proj-1",
            mask_id="mask-1",
            request_options=None,
        )


class TestAgentConfigManagerGetBlueprintByName:
    def test_get_blueprint_by_name__returns_blueprint(
        self, agent_config, mock_rest_client
    ):
        mock_rest_client.projects.retrieve_project.return_value = mock.Mock(id="proj-1")
        mock_rest_client.agent_configs.get_blueprint_by_name.return_value = (
            _make_raw_blueprint(blueprint_id="bp-specific")
        )

        result = agent_config.get_blueprint(name="v1")

        assert isinstance(result, Blueprint)
        assert result.id == "bp-specific"
        mock_rest_client.agent_configs.get_blueprint_by_name.assert_called_once_with(
            project_id="proj-1", name="v1", mask_id=None, request_options=None
        )

    def test_get_blueprint_by_name__not_found__returns_none(
        self, agent_config, mock_rest_client
    ):
        mock_rest_client.projects.retrieve_project.return_value = mock.Mock(id="proj-1")
        mock_rest_client.agent_configs.get_blueprint_by_name.side_effect = (
            rest_api_core.ApiError(status_code=404, body="not found")
        )

        result = agent_config.get_blueprint(name="nonexistent")

        assert result is None


class TestAgentConfigManagerCreateBlueprint:
    def test_create_blueprint__happy_path__calls_backend_and_returns_blueprint(
        self, agent_config, mock_rest_client
    ):
        mock_rest_client.agent_configs.get_blueprint_by_id.return_value = (
            _make_raw_blueprint(blueprint_id="bp-new")
        )

        result = agent_config.create_blueprint(
            fields_with_values={
                "temperature": (float, 0.6, None),
                "name": (str, "agent", None),
            }
        )

        mock_rest_client.agent_configs.create_agent_config.assert_called_once()
        call_kwargs = mock_rest_client.agent_configs.create_agent_config.call_args[1]
        blueprint = call_kwargs["blueprint"]
        assert blueprint.type == "blueprint"
        assert blueprint.values is not None
        assert blueprint.id is not None
        assert isinstance(result, Blueprint)

    def test_create_blueprint__bool_field__serialized_as_boolean_type(
        self, agent_config, mock_rest_client
    ):
        mock_rest_client.agent_configs.get_blueprint_by_id.return_value = (
            _make_raw_blueprint()
        )

        agent_config.create_blueprint(fields_with_values={"flag": (bool, False, None)})

        call_kwargs = mock_rest_client.agent_configs.create_agent_config.call_args[1]
        blueprint = call_kwargs["blueprint"]
        flag_param = [v for v in blueprint.values if v.key == "flag"][0]
        assert flag_param.type == "boolean"
        assert flag_param.value == "false"

    def test_create_blueprint__with_project_name__passes_project_to_backend(
        self, agent_config, mock_rest_client
    ):
        mock_rest_client.agent_configs.get_blueprint_by_id.return_value = (
            _make_raw_blueprint()
        )

        agent_config.create_blueprint(fields_with_values={"temp": (float, 0.5, None)})

        call_kwargs = mock_rest_client.agent_configs.create_agent_config.call_args[1]
        assert call_kwargs["project_name"] == "my-project"

    def test_create_blueprint__with_parameters__returns_blueprint(
        self, agent_config, mock_rest_client
    ):
        mock_rest_client.agent_configs.get_blueprint_by_id.return_value = (
            _make_raw_blueprint(blueprint_id="bp-new")
        )

        result = agent_config.create_blueprint(
            parameters={"temp": 0.6, "name": "agent"}
        )

        assert isinstance(result, Blueprint)
        assert result.id == "bp-new"
        call_kwargs = mock_rest_client.agent_configs.create_agent_config.call_args[1]
        bp = call_kwargs["blueprint"]
        keys = {v.key for v in bp.values}
        assert "temp" in keys
        assert "name" in keys

    def test_create_blueprint__with_fields_with_values__returns_blueprint(
        self, agent_config, mock_rest_client
    ):
        mock_rest_client.agent_configs.get_blueprint_by_id.return_value = (
            _make_raw_blueprint()
        )

        result = agent_config.create_blueprint(
            fields_with_values={"temp": (float, 0.6, None)}
        )

        assert isinstance(result, Blueprint)
        call_kwargs = mock_rest_client.agent_configs.create_agent_config.call_args[1]
        keys = {v.key for v in call_kwargs["blueprint"].values}
        assert "temp" in keys

    def test_create_blueprint__with_description__passes_description(
        self, agent_config, mock_rest_client
    ):
        mock_rest_client.agent_configs.get_blueprint_by_id.return_value = (
            _make_raw_blueprint()
        )

        agent_config.create_blueprint(parameters={"temp": 0.6}, description="v1")

        call_kwargs = mock_rest_client.agent_configs.create_agent_config.call_args[1]
        assert call_kwargs["blueprint"].description == "v1"


class TestAgentConfigManagerCreateMask:
    def test_create_mask__happy_path__calls_backend_with_mask_type(
        self, agent_config, mock_rest_client
    ):
        agent_config.create_mask(fields_with_values={"temperature": (float, 0.3, None)})

        call_kwargs = mock_rest_client.agent_configs.create_agent_config.call_args[1]
        blueprint = call_kwargs["blueprint"]
        assert blueprint.type == "mask"
        assert blueprint.values is not None

    def test_create_mask__returns_mask_id(self, agent_config, mock_rest_client):
        result = agent_config.create_mask(
            fields_with_values={"temperature": (float, 0.3, None)}
        )

        assert isinstance(result, str)
        mock_rest_client.agent_configs.get_blueprint_by_id.assert_not_called()

    def test_create_mask__sends_under_blueprint_key(
        self, agent_config, mock_rest_client
    ):
        agent_config.create_mask(fields_with_values={"temperature": (float, 0.3, None)})

        call_kwargs = mock_rest_client.agent_configs.create_agent_config.call_args[1]
        assert "blueprint" in call_kwargs
        assert call_kwargs["blueprint"].id is not None

    def test_create_mask__with_parameters__returns_mask_id(
        self, agent_config, mock_rest_client
    ):
        result = agent_config.create_mask(parameters={"temp": 0.3})

        assert isinstance(result, str)

    def test_create_mask__with_description__passes_description(
        self, agent_config, mock_rest_client
    ):
        agent_config.create_mask(
            fields_with_values={"temperature": (float, 0.3, None)},
            description="variant-A",
        )

        call_kwargs = mock_rest_client.agent_configs.create_agent_config.call_args[1]
        assert call_kwargs["blueprint"].description == "variant-A"

    def test_create_mask__with_project_name__passes_project_to_backend(
        self, agent_config, mock_rest_client
    ):
        agent_config.create_mask(fields_with_values={"temp": (float, 0.5, None)})

        call_kwargs = mock_rest_client.agent_configs.create_agent_config.call_args[1]
        assert call_kwargs["project_name"] == "my-project"


class TestResolveFieldsWithValues:
    def test_none_values_are_excluded(self):
        result = AgentConfigManager._resolve_fields_with_values(
            parameters={"temp": 0.5, "name": None},
            fields_with_values=None,
        )
        assert "name" not in result
        assert result["temp"] == (float, 0.5, None)

    def test_all_none_parameters_returns_empty_dict(self):
        result = AgentConfigManager._resolve_fields_with_values(
            parameters={"a": None, "b": None},
            fields_with_values=None,
        )
        assert result == {}

    def test_fields_with_values_takes_precedence_over_parameters(self):
        explicit = {"x": (int, 1, None)}
        result = AgentConfigManager._resolve_fields_with_values(
            parameters={"x": 99},
            fields_with_values=explicit,
        )
        assert result is explicit

    def test_create_blueprint__none_parameter__excluded_from_payload(
        self, agent_config, mock_rest_client
    ):
        mock_rest_client.agent_configs.get_blueprint_by_id.return_value = (
            _make_raw_blueprint()
        )

        agent_config.create_blueprint(parameters={"temp": 0.6, "name": None})

        call_kwargs = mock_rest_client.agent_configs.create_agent_config.call_args[1]
        keys = {v.key for v in call_kwargs["blueprint"].values}
        assert "temp" in keys
        assert "name" not in keys
