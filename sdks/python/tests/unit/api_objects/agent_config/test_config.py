from unittest import mock

import pytest

from opik.api_objects.agent_config.config import AgentConfig
from opik.api_objects.agent_config.client import ConfigData


@pytest.fixture
def sample_config():
    return AgentConfig.from_backend_data(
        config_data=ConfigData(
            blueprint_id="bp-1",
            values={"temp": 0.6, "name": "agent", "count": 10},
        ),
    )


class TestConfigFromBackendData:
    def test_from_backend_data__happy_path__sets_all_properties(self):
        config_data = ConfigData(
            blueprint_id="bp-1",
            values={"temperature": 0.6, "name": "agent"},
        )

        config = AgentConfig.from_backend_data(config_data=config_data)

        assert config.blueprint_id == "bp-1"
        assert config.values == {"temperature": 0.6, "name": "agent"}

    def test_from_backend_data__values_property__returns_deep_copy(self):
        config_data = ConfigData(
            blueprint_id="bp-1",
            values={"items": [1, 2, 3]},
        )

        config = AgentConfig.from_backend_data(config_data=config_data)
        values = config.values
        values["items"].append(4)

        assert config.values["items"] == [1, 2, 3]


class TestConfigDictLikeAccess:
    @pytest.mark.parametrize(
        "key, expected",
        [
            ("temp", 0.6),
            ("name", "agent"),
            ("count", 10),
        ],
        ids=["float_value", "str_value", "int_value"],
    )
    def test_get__existing_key__returns_value(self, sample_config, key, expected):
        assert sample_config.get(key) == expected

    @pytest.mark.parametrize(
        "default",
        [None, 42, "fallback"],
        ids=["default_none", "default_int", "default_str"],
    )
    def test_get__missing_key__returns_default(self, sample_config, default):
        assert sample_config.get("missing", default) == default

    def test_get__missing_key_no_default__returns_none(self, sample_config):
        assert sample_config.get("missing") is None

    def test_getitem__existing_key__returns_value(self, sample_config):
        assert sample_config["name"] == "agent"

    def test_getitem__missing_key__raises_key_error(self, sample_config):
        with pytest.raises(KeyError):
            _ = sample_config["missing"]

    def test_keys__returns_all_value_keys(self, sample_config):
        assert set(sample_config.keys()) == {"temp", "name", "count"}


class TestConfigInit:
    @mock.patch("opik.api_objects.opik_client.get_client_cached")
    def test_init__happy_path__syncs_with_backend(self, mock_get_client):
        mock_client = mock.Mock()
        mock_client._project_name = "default-project"
        mock_get_client.return_value = mock_client

        mock_client.rest_client.agent_configs.create_agent_config.return_value = None
        mock_client.rest_client.agent_configs.get_latest_blueprint.return_value = (
            mock.Mock(
                id="bp-new",
                values=[
                    mock.Mock(key="temperature", type="number", value=0.8),
                ],
                description=None,
            )
        )
        mock_client.rest_client.projects.retrieve_project.return_value = mock.Mock(
            id="proj-1"
        )

        config = AgentConfig(parameters={"temperature": 0.8})

        assert config.blueprint_id == "bp-new"
        mock_client.rest_client.agent_configs.create_agent_config.assert_called_once()


class TestConfigUpdate:
    @mock.patch("opik.api_objects.opik_client.get_client_cached")
    def test_update__posts_new_blueprint_and_updates_state(self, mock_get_client):
        config = AgentConfig.from_backend_data(
            config_data=ConfigData(
                blueprint_id="bp-1",
                values={"temp": 0.6},
            ),
        )

        mock_client = mock.Mock()
        mock_client._project_name = "default-project"
        mock_get_client.return_value = mock_client

        mock_client.rest_client.agent_configs.create_agent_config.return_value = None
        mock_client.rest_client.agent_configs.get_latest_blueprint.return_value = (
            mock.Mock(
                id="bp-2",
                values=[mock.Mock(key="temp", type="number", value=0.9)],
                description="Updated",
            )
        )
        mock_client.rest_client.projects.retrieve_project.return_value = mock.Mock(
            id="proj-1"
        )

        config.update(values={"temp": 0.9}, description="Updated")

        mock_client.rest_client.agent_configs.create_agent_config.assert_called_once()
        assert config.blueprint_id == "bp-2"
        assert config["temp"] == 0.9

    @mock.patch("opik.api_objects.opik_client.get_client_cached")
    def test_update__passes_description_to_blueprint(self, mock_get_client):
        config = AgentConfig.from_backend_data(
            config_data=ConfigData(blueprint_id="bp-1", values={"temp": 0.6}),
        )

        mock_client = mock.Mock()
        mock_client._project_name = "default-project"
        mock_get_client.return_value = mock_client
        mock_client.rest_client.agent_configs.create_agent_config.return_value = None
        mock_client.rest_client.agent_configs.get_latest_blueprint.return_value = (
            mock.Mock(id="bp-2", values=[], description="bump")
        )
        mock_client.rest_client.projects.retrieve_project.return_value = mock.Mock(
            id="proj-1"
        )

        config.update(values={"temp": 0.9}, description="bump")

        call_kwargs = (
            mock_client.rest_client.agent_configs.create_agent_config.call_args[1]
        )
        assert call_kwargs["blueprint"].description == "bump"

    @mock.patch("opik.api_objects.opik_client.get_client_cached")
    def test_update__passes_project_id_to_backend(self, mock_get_client):
        config = AgentConfig.from_backend_data(
            config_data=ConfigData(blueprint_id="bp-1", values={"temp": 0.6}),
        )

        mock_client = mock.Mock()
        mock_client._project_name = "default-project"
        mock_get_client.return_value = mock_client
        mock_client.rest_client.agent_configs.create_agent_config.return_value = None
        mock_client.rest_client.agent_configs.get_latest_blueprint.return_value = (
            mock.Mock(id="bp-2", values=[], description=None)
        )
        mock_client.rest_client.projects.retrieve_project.return_value = mock.Mock(
            id="proj-1"
        )

        config.update(values={"temp": 0.9}, project_id="proj-42")

        call_kwargs = (
            mock_client.rest_client.agent_configs.create_agent_config.call_args[1]
        )
        assert call_kwargs["project_id"] == "proj-42"


class TestConfigCreateMask:
    def _make_mock_client(self, mock_get_client, blueprint_id="bp-mask", values=None):
        mock_client = mock.Mock()
        mock_client._project_name = "default-project"
        mock_get_client.return_value = mock_client

        mock_client.rest_client.agent_configs.create_agent_config.return_value = None
        mock_client.rest_client.agent_configs.get_latest_blueprint.return_value = (
            mock.Mock(
                id=blueprint_id,
                values=values
                or [mock.Mock(key="temperature", type="number", value=0.3)],
                description=None,
            )
        )
        mock_client.rest_client.projects.retrieve_project.return_value = mock.Mock(
            id="proj-1"
        )
        return mock_client

    @mock.patch("opik.api_objects.opik_client.get_client_cached")
    def test_create_mask__calls_backend_with_mask_type(self, mock_get_client):
        config = AgentConfig.from_backend_data(
            config_data=ConfigData(blueprint_id="bp-1", values={"temperature": 0.8}),
        )
        mock_client = self._make_mock_client(mock_get_client)

        config.create_mask(values={"temperature": 0.3})

        call_kwargs = (
            mock_client.rest_client.agent_configs.create_agent_config.call_args[1]
        )
        assert call_kwargs["blueprint"].type == "mask"
        assert call_kwargs["blueprint"].values is not None

    @mock.patch("opik.api_objects.opik_client.get_client_cached")
    def test_create_mask__returns_new_agent_config_instance(self, mock_get_client):
        config = AgentConfig.from_backend_data(
            config_data=ConfigData(blueprint_id="bp-1", values={"temperature": 0.8}),
        )
        self._make_mock_client(mock_get_client, blueprint_id="bp-mask")

        mask = config.create_mask(values={"temperature": 0.3})

        assert isinstance(mask, AgentConfig)
        assert mask.blueprint_id == "bp-mask"

    @mock.patch("opik.api_objects.opik_client.get_client_cached")
    def test_create_mask__does_not_mutate_original_config(self, mock_get_client):
        config = AgentConfig.from_backend_data(
            config_data=ConfigData(blueprint_id="bp-1", values={"temperature": 0.8}),
        )
        self._make_mock_client(mock_get_client)

        config.create_mask(values={"temperature": 0.3})

        assert config.blueprint_id == "bp-1"
        assert config["temperature"] == 0.8

    @mock.patch("opik.api_objects.opik_client.get_client_cached")
    def test_create_mask__with_description__passes_description_to_backend(
        self, mock_get_client
    ):
        config = AgentConfig.from_backend_data(
            config_data=ConfigData(blueprint_id="bp-1", values={"temperature": 0.8}),
        )
        mock_client = self._make_mock_client(mock_get_client)

        config.create_mask(values={"temperature": 0.3}, description="variant-B")

        call_kwargs = (
            mock_client.rest_client.agent_configs.create_agent_config.call_args[1]
        )
        assert call_kwargs["blueprint"].description == "variant-B"

    @mock.patch("opik.api_objects.opik_client.get_client_cached")
    def test_create_mask__with_project_id__passes_project_id_to_backend(
        self, mock_get_client
    ):
        config = AgentConfig.from_backend_data(
            config_data=ConfigData(blueprint_id="bp-1", values={"temperature": 0.8}),
        )
        mock_client = self._make_mock_client(mock_get_client)

        config.create_mask(values={"temperature": 0.3}, project_id="proj-42")

        call_kwargs = (
            mock_client.rest_client.agent_configs.create_agent_config.call_args[1]
        )
        assert call_kwargs["project_id"] == "proj-42"


class TestConfigGetBlueprint:
    def _make_mock_client(
        self, mock_get_client, blueprint_id="bp-fetched", values=None
    ):
        mock_client = mock.Mock()
        mock_client._project_name = "default-project"
        mock_get_client.return_value = mock_client

        mock_client.rest_client.agent_configs.get_latest_blueprint.return_value = (
            mock.Mock(
                id=blueprint_id,
                values=values
                or [mock.Mock(key="temperature", type="number", value=0.7)],
                description=None,
            )
        )
        mock_client.rest_client.agent_configs.get_blueprint_by_env.return_value = (
            mock.Mock(
                id=blueprint_id,
                values=values
                or [mock.Mock(key="temperature", type="number", value=0.7)],
                description=None,
            )
        )
        mock_client.rest_client.projects.retrieve_project.return_value = mock.Mock(
            id="proj-1"
        )
        return mock_client

    @mock.patch("opik.api_objects.opik_client.get_client_cached")
    def test_get_blueprint__happy_path__returns_agent_config(self, mock_get_client):
        self._make_mock_client(mock_get_client, blueprint_id="bp-fetched")

        config = AgentConfig.get_blueprint(project_name="my-project")

        assert isinstance(config, AgentConfig)
        assert config.blueprint_id == "bp-fetched"

    @mock.patch("opik.api_objects.opik_client.get_client_cached")
    def test_get_blueprint__with_env__routes_to_get_blueprint_by_env(
        self, mock_get_client
    ):
        mock_client = self._make_mock_client(mock_get_client)

        AgentConfig.get_blueprint(project_name="my-project", env="prod")

        mock_client.rest_client.agent_configs.get_blueprint_by_env.assert_called_once()
        call_kwargs = (
            mock_client.rest_client.agent_configs.get_blueprint_by_env.call_args[1]
        )
        assert call_kwargs.get("env_name") == "prod"

    @mock.patch("opik.api_objects.opik_client.get_client_cached")
    def test_get_blueprint__with_mask_id__passes_mask_id_to_backend(
        self, mock_get_client
    ):
        mock_client = self._make_mock_client(mock_get_client)

        AgentConfig.get_blueprint(project_name="my-project", mask_id="mask-xyz")

        call_kwargs = (
            mock_client.rest_client.agent_configs.get_latest_blueprint.call_args[1]
        )
        assert call_kwargs.get("mask_id") == "mask-xyz"

    @mock.patch("opik.api_objects.opik_client.get_client_cached")
    def test_get_blueprint__no_project_name__uses_client_default_project(
        self, mock_get_client
    ):
        mock_client = self._make_mock_client(mock_get_client)
        mock_client._project_name = "client-default"

        AgentConfig.get_blueprint()

        mock_client.rest_client.projects.retrieve_project.assert_called_once_with(
            name="client-default"
        )

    @mock.patch("opik.api_objects.opik_client.get_client_cached")
    def test_get_blueprint__not_found__raises_value_error(self, mock_get_client):
        from opik.rest_api import core as rest_api_core

        mock_client = mock.Mock()
        mock_client._project_name = "default-project"
        mock_get_client.return_value = mock_client
        mock_client.rest_client.agent_configs.get_latest_blueprint.side_effect = (
            rest_api_core.ApiError(status_code=404, body="not found")
        )
        mock_client.rest_client.projects.retrieve_project.return_value = mock.Mock(
            id="proj-1"
        )

        with pytest.raises(ValueError):
            AgentConfig.get_blueprint(project_name="my-project")
