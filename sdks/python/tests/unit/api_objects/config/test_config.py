from unittest import mock

import pytest

from opik.api_objects.config.config import Config
from opik.api_objects.config.client import ConfigData


@pytest.fixture
def sample_config():
    return Config.from_backend_data(
        config_data=ConfigData(
            config_id="cfg-1",
            blueprint_id="bp-1",
            values={"temp": 0.6, "name": "agent", "count": 10},
        ),
    )


class TestConfigFromBackendData:
    def test_from_backend_data__happy_path__sets_all_properties(self):
        config_data = ConfigData(
            config_id="cfg-1",
            blueprint_id="bp-1",
            values={"temperature": 0.6, "name": "agent"},
        )

        config = Config.from_backend_data(config_data=config_data)

        assert config.config_id == "cfg-1"
        assert config.blueprint_id == "bp-1"
        assert config.values == {"temperature": 0.6, "name": "agent"}

    def test_from_backend_data__values_property__returns_deep_copy(self):
        config_data = ConfigData(
            config_id="cfg-1",
            blueprint_id="bp-1",
            values={"items": [1, 2, 3]},
        )

        config = Config.from_backend_data(config_data=config_data)
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
        mock_get_client.return_value = mock_client

        mock_client.rest_client.optimizer_configs.create_config.return_value = (
            mock.Mock(id="cfg-new")
        )
        mock_client.rest_client.optimizer_configs.get_blueprint.return_value = (
            mock.Mock(
                id="bp-new",
                values=[
                    mock.Mock(key="temperature", type="number", value=0.8),
                ],
                description=None,
            )
        )

        config = Config(parameters={"temperature": 0.8})

        assert config.config_id == "cfg-new"
        assert config.blueprint_id == "bp-new"
        mock_client.rest_client.optimizer_configs.create_config.assert_called_once()


class TestConfigLogValues:
    @mock.patch("opik.api_objects.opik_client.get_client_cached")
    def test_log_values__happy_path__calls_update_and_applies_result(
        self, mock_get_client
    ):
        config = Config.from_backend_data(
            config_data=ConfigData(
                config_id="cfg-1",
                blueprint_id="bp-1",
                values={"temp": 0.6},
            ),
        )

        mock_client = mock.Mock()
        mock_get_client.return_value = mock_client

        mock_client.rest_client.optimizer_configs.update_values.return_value = (
            mock.Mock(
                id="bp-2",
                values=[
                    mock.Mock(key="temp", type="number", value=0.9),
                ],
                description="Updated",
            )
        )

        config.log_values(
            values={"temp": 0.9},
            description="Updated",
        )

        mock_client.rest_client.optimizer_configs.update_values.assert_called_once()
        assert config.blueprint_id == "bp-2"


class TestConfigUpdateEnvs:
    @mock.patch("opik.api_objects.opik_client.get_client_cached")
    def test_update_envs__multiple_envs__calls_assign(self, mock_get_client):
        config = Config.from_backend_data(
            config_data=ConfigData(
                config_id="cfg-1",
                blueprint_id="bp-1",
                values={"temp": 0.6},
            ),
        )

        mock_client = mock.Mock()
        mock_get_client.return_value = mock_client

        config.update_envs(["prod", "staging"])

        mock_client.rest_client.optimizer_configs.assign_envs.assert_called_once()
        call_kwargs = mock_client.rest_client.optimizer_configs.assign_envs.call_args[1]
        assert len(call_kwargs["envs"]) == 2
