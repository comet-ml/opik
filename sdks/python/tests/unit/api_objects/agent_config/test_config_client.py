from unittest import mock

import pytest

from opik.api_objects.agent_config.client import ConfigClient
from opik.rest_api.types.agent_blueprint_public import AgentBlueprintPublic
from opik.rest_api.types.agent_config_value_public import AgentConfigValuePublic


def _make_blueprint(
    blueprint_id="bp-456",
    values=None,
    description=None,
):
    if values is None:
        values = [
            AgentConfigValuePublic(key="temperature", type="number", value="0.6"),
            AgentConfigValuePublic(key="name", type="string", value="agent"),
        ]
    return AgentBlueprintPublic(
        id=blueprint_id,
        type="blueprint",
        values=values,
        description=description,
    )


@pytest.fixture
def mock_rest_client():
    client = mock.Mock()
    client.agent_configs = mock.Mock()
    client.agent_configs.create_agent_config.return_value = None
    client.agent_configs.get_latest_blueprint.return_value = _make_blueprint()
    client.projects.retrieve_project.return_value = mock.Mock(id="proj-default")
    return client


@pytest.fixture
def config_client(mock_rest_client):
    return ConfigClient(mock_rest_client)


class TestCreateConfig:
    def test_create__happy_path__calls_backend_and_returns_raw_blueprint(
        self, config_client, mock_rest_client
    ):
        mock_rest_client.agent_configs.get_blueprint_by_id.return_value = (
            _make_blueprint()
        )

        result = config_client.create_config(
            fields_with_values={
                "temperature": (float, 0.6),
                "name": (str, "agent"),
            },
            project_name="my-project",
        )

        mock_rest_client.agent_configs.create_agent_config.assert_called_once()
        call_kwargs = mock_rest_client.agent_configs.create_agent_config.call_args[1]
        blueprint = call_kwargs["blueprint"]
        assert blueprint.type == "blueprint"
        assert blueprint.values is not None
        assert call_kwargs["blueprint"].id is not None
        mock_rest_client.agent_configs.get_blueprint_by_id.assert_called_once_with(
            call_kwargs["blueprint"].id
        )

        assert isinstance(result, AgentBlueprintPublic)
        assert result.id == "bp-456"

    def test_create__bool_field__serialized_as_string_type(
        self, config_client, mock_rest_client
    ):
        mock_rest_client.agent_configs.get_blueprint_by_id.return_value = (
            _make_blueprint()
        )

        config_client.create_config(
            fields_with_values={"flag": (bool, False)},
            project_name="my-project",
        )

        call_kwargs = mock_rest_client.agent_configs.create_agent_config.call_args[1]
        blueprint = call_kwargs["blueprint"]
        flag_param = [v for v in blueprint.values if v.key == "flag"][0]
        assert flag_param.type == "string"
        assert flag_param.value == "false"

    def test_create__with_project_name__passes_project_to_backend(
        self, config_client, mock_rest_client
    ):
        mock_rest_client.agent_configs.get_blueprint_by_id.return_value = (
            _make_blueprint()
        )

        config_client.create_config(
            fields_with_values={"temp": (float, 0.5)},
            project_name="my-project",
        )

        call_kwargs = mock_rest_client.agent_configs.create_agent_config.call_args[1]
        assert call_kwargs["project_name"] == "my-project"

    def test_create__get_blueprint_uses_client_generated_id(
        self, config_client, mock_rest_client
    ):
        mock_rest_client.agent_configs.get_blueprint_by_id.return_value = (
            _make_blueprint()
        )

        config_client.create_config(
            fields_with_values={"temp": (float, 0.5)},
            project_name="my-project",
        )

        create_kwargs = mock_rest_client.agent_configs.create_agent_config.call_args[1]
        generated_id = create_kwargs["blueprint"].id
        mock_rest_client.agent_configs.get_blueprint_by_id.assert_called_once_with(
            generated_id
        )

    def test_create__with_project_id__passes_project_id_to_backend(
        self, config_client, mock_rest_client
    ):
        mock_rest_client.agent_configs.get_blueprint_by_id.return_value = (
            _make_blueprint()
        )

        config_client.create_config(
            fields_with_values={"temp": (float, 0.5)},
            project_name="my-project",
            project_id="proj-99",
        )

        call_kwargs = mock_rest_client.agent_configs.create_agent_config.call_args[1]
        assert call_kwargs["project_id"] == "proj-99"


class TestGetBlueprint:
    def test_get_blueprint__happy_path__returns_raw_blueprint(
        self, config_client, mock_rest_client
    ):
        mock_rest_client.agent_configs.get_latest_blueprint.return_value = (
            _make_blueprint(blueprint_id="bp-789")
        )
        mock_rest_client.projects.retrieve_project.return_value = mock.Mock(id="proj-1")

        result = config_client.get_blueprint(project_name="my-project")

        assert isinstance(result, AgentBlueprintPublic)
        assert result.id == "bp-789"

    @pytest.mark.parametrize(
        "mask_id",
        ["mask-1", "mask-2", None],
        ids=["mask_1", "mask_2", "no_mask"],
    )
    def test_get_blueprint__mask_id__passed_to_backend(
        self, config_client, mock_rest_client, mask_id
    ):
        mock_rest_client.agent_configs.get_latest_blueprint.return_value = (
            _make_blueprint()
        )
        mock_rest_client.projects.retrieve_project.return_value = mock.Mock(id="proj-1")

        config_client.get_blueprint(
            project_name="my-project",
            mask_id=mask_id,
        )

        mock_rest_client.agent_configs.get_latest_blueprint.assert_called_once_with(
            project_id="proj-1",
            mask_id=mask_id,
        )

    def test_get_blueprint__env__routes_to_get_blueprint_by_env(
        self, config_client, mock_rest_client
    ):
        mock_rest_client.agent_configs.get_blueprint_by_env.return_value = (
            _make_blueprint()
        )
        mock_rest_client.projects.retrieve_project.return_value = mock.Mock(id="proj-1")

        config_client.get_blueprint(
            project_name="my-project",
            env="prod",
        )

        mock_rest_client.agent_configs.get_blueprint_by_env.assert_called_once_with(
            env_name="prod",
            project_id="proj-1",
            mask_id=None,
        )
        mock_rest_client.agent_configs.get_latest_blueprint.assert_not_called()

    def test_get_blueprint__env_with_mask_id__routes_to_get_blueprint_by_env(
        self, config_client, mock_rest_client
    ):
        mock_rest_client.agent_configs.get_blueprint_by_env.return_value = (
            _make_blueprint()
        )
        mock_rest_client.projects.retrieve_project.return_value = mock.Mock(id="proj-1")

        config_client.get_blueprint(
            project_name="my-project",
            env="prod",
            mask_id="mask-1",
        )

        mock_rest_client.agent_configs.get_blueprint_by_env.assert_called_once_with(
            env_name="prod",
            project_id="proj-1",
            mask_id="mask-1",
        )


class TestTryGetBlueprint:
    def test_try_get_blueprint__not_found__returns_none(
        self, config_client, mock_rest_client
    ):
        from opik.rest_api import core as rest_api_core

        mock_rest_client.agent_configs.get_latest_blueprint.side_effect = (
            rest_api_core.ApiError(status_code=404, body="not found")
        )
        mock_rest_client.projects.retrieve_project.return_value = mock.Mock(id="proj-1")

        result = config_client.try_get_blueprint(project_name="my-project")

        assert result is None

    def test_try_get_blueprint__found__returns_raw_blueprint(
        self, config_client, mock_rest_client
    ):
        mock_rest_client.agent_configs.get_latest_blueprint.return_value = (
            _make_blueprint(blueprint_id="bp-found")
        )
        mock_rest_client.projects.retrieve_project.return_value = mock.Mock(id="proj-1")

        result = config_client.try_get_blueprint(project_name="my-project")

        assert isinstance(result, AgentBlueprintPublic)
        assert result.id == "bp-found"


class TestCreateMask:
    def test_create_mask__happy_path__calls_backend_with_mask_type(
        self, config_client, mock_rest_client
    ):
        config_client.create_mask(
            fields_with_values={"temperature": (float, 0.3)},
            project_name="my-project",
        )

        call_kwargs = mock_rest_client.agent_configs.create_agent_config.call_args[1]
        blueprint = call_kwargs["blueprint"]
        assert blueprint.type == "mask"
        assert blueprint.values is not None

    def test_create_mask__returns_mask_id(self, config_client, mock_rest_client):
        result = config_client.create_mask(
            fields_with_values={"temperature": (float, 0.3)},
            project_name="my-project",
        )

        assert isinstance(result, str)
        mock_rest_client.agent_configs.get_blueprint_by_id.assert_not_called()

    def test_create_mask__sends_under_blueprint_key(
        self, config_client, mock_rest_client
    ):
        config_client.create_mask(
            fields_with_values={"temperature": (float, 0.3)},
            project_name="my-project",
        )

        call_kwargs = mock_rest_client.agent_configs.create_agent_config.call_args[1]
        assert "blueprint" in call_kwargs
        assert call_kwargs["blueprint"].id is not None

    def test_create_mask__with_description__passes_description_in_mask_payload(
        self, config_client, mock_rest_client
    ):
        config_client.create_mask(
            fields_with_values={"temperature": (float, 0.3)},
            project_name="my-project",
            description="variant-A",
        )

        call_kwargs = mock_rest_client.agent_configs.create_agent_config.call_args[1]
        assert call_kwargs["blueprint"].description == "variant-A"

    def test_create_mask__with_project_id__passes_project_id_to_backend(
        self, config_client, mock_rest_client
    ):
        config_client.create_mask(
            fields_with_values={"temperature": (float, 0.3)},
            project_name="my-project",
            project_id="proj-99",
        )

        call_kwargs = mock_rest_client.agent_configs.create_agent_config.call_args[1]
        assert call_kwargs["project_id"] == "proj-99"
