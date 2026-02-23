from unittest import mock

import pytest

from opik.api_objects.config.client import ConfigClient, ConfigData
from opik.rest_api.types.optimizer_config_detail import (
    OptimizerConfigBlueprint,
    OptimizerConfigCreateResponse,
    OptimizerConfigParameter,
)


def _make_create_response(config_id="cfg-123"):
    return OptimizerConfigCreateResponse(id=config_id)


def _make_blueprint(
    blueprint_id="bp-456",
    values=None,
    description=None,
):
    if values is None:
        values = [
            OptimizerConfigParameter(key="temperature", type="number", value=0.6),
            OptimizerConfigParameter(key="name", type="string", value="agent"),
        ]
    return OptimizerConfigBlueprint(
        id=blueprint_id,
        values=values,
        description=description,
    )


@pytest.fixture
def mock_rest_client():
    client = mock.Mock()
    client.optimizer_configs = mock.Mock()
    return client


@pytest.fixture
def config_client(mock_rest_client):
    return ConfigClient(mock_rest_client)


class TestCreateConfig:
    def test_create__happy_path__calls_backend_and_returns_config_data(
        self, config_client, mock_rest_client
    ):
        mock_rest_client.optimizer_configs.create_config.return_value = (
            _make_create_response("cfg-1")
        )
        mock_rest_client.optimizer_configs.get_blueprint.return_value = (
            _make_blueprint()
        )

        result = config_client.create_config(
            name="TestConfig",
            fields_with_values={
                "temperature": (float, 0.6),
                "name": (str, "agent"),
            },
        )

        mock_rest_client.optimizer_configs.create_config.assert_called_once()
        call_kwargs = mock_rest_client.optimizer_configs.create_config.call_args[1]
        assert call_kwargs["blueprint"]["values"] is not None

        mock_rest_client.optimizer_configs.get_blueprint.assert_called_once_with(
            "cfg-1", mask_id=None, env=None
        )

        assert isinstance(result, ConfigData)
        assert result.config_id == "cfg-1"

    def test_create__bool_field__serialized_as_string_type(
        self, config_client, mock_rest_client
    ):
        mock_rest_client.optimizer_configs.create_config.return_value = (
            _make_create_response("cfg-2")
        )
        mock_rest_client.optimizer_configs.get_blueprint.return_value = (
            _make_blueprint()
        )

        config_client.create_config(
            name="TestConfig",
            fields_with_values={"flag": (bool, False)},
        )

        call_kwargs = mock_rest_client.optimizer_configs.create_config.call_args[1]
        values = call_kwargs["blueprint"]["values"]
        flag_param = [v for v in values if v["key"] == "flag"][0]
        assert flag_param["type"] == "string"
        assert flag_param["value"] == "false"

    def test_create__with_project_name__passes_project_to_backend(
        self, config_client, mock_rest_client
    ):
        mock_rest_client.optimizer_configs.create_config.return_value = (
            _make_create_response()
        )
        mock_rest_client.optimizer_configs.get_blueprint.return_value = (
            _make_blueprint()
        )

        config_client.create_config(
            name="TestConfig",
            fields_with_values={"temp": (float, 0.5)},
            project_name="my-project",
        )

        call_kwargs = mock_rest_client.optimizer_configs.create_config.call_args[1]
        assert call_kwargs["project_name"] == "my-project"

    def test_create__backend_returns_no_id__raises_value_error(
        self, config_client, mock_rest_client
    ):
        mock_rest_client.optimizer_configs.create_config.return_value = (
            OptimizerConfigCreateResponse(id=None)
        )

        with pytest.raises(ValueError, match="Backend returned no config_id"):
            config_client.create_config(
                name="Test",
                fields_with_values={"x": (str, "y")},
            )


class TestGetBlueprint:
    def test_get_blueprint__happy_path__returns_config_data(
        self, config_client, mock_rest_client
    ):
        mock_rest_client.optimizer_configs.get_blueprint.return_value = _make_blueprint(
            blueprint_id="bp-789"
        )

        result = config_client.get_blueprint(config_id="cfg-1")

        assert result.config_id == "cfg-1"
        assert result.blueprint_id == "bp-789"
        assert "temperature" in result.values
        assert "name" in result.values

    def test_get_blueprint__with_field_types__deserializes_values(
        self, config_client, mock_rest_client
    ):
        mock_rest_client.optimizer_configs.get_blueprint.return_value = _make_blueprint(
            values=[
                OptimizerConfigParameter(key="temperature", type="number", value="0.6"),
                OptimizerConfigParameter(key="flag", type="string", value="true"),
            ]
        )

        result = config_client.get_blueprint(
            config_id="cfg-1",
            field_types={"temperature": float, "flag": bool},
        )

        assert result.values["temperature"] == 0.6
        assert result.values["flag"] is True

    @pytest.mark.parametrize(
        "mask_id, env",
        [
            ("mask-1", "prod"),
            (None, "staging"),
            ("mask-2", None),
        ],
        ids=["both_mask_and_env", "env_only", "mask_only"],
    )
    def test_get_blueprint__mask_id_and_env__passed_to_backend(
        self, config_client, mock_rest_client, mask_id, env
    ):
        mock_rest_client.optimizer_configs.get_blueprint.return_value = (
            _make_blueprint()
        )

        config_client.get_blueprint(
            config_id="cfg-1",
            mask_id=mask_id,
            env=env,
        )

        mock_rest_client.optimizer_configs.get_blueprint.assert_called_once_with(
            "cfg-1", mask_id=mask_id, env=env
        )


class TestUpdateValues:
    def test_update_values__happy_path__sends_correct_payload(
        self, config_client, mock_rest_client
    ):
        mock_rest_client.optimizer_configs.update_values.return_value = (
            _make_blueprint()
        )

        config_client.update_values(
            config_id="cfg-1",
            values={"temperature": (float, 0.9)},
            description="Bump temp",
        )

        mock_rest_client.optimizer_configs.update_values.assert_called_once()
        call_kwargs = mock_rest_client.optimizer_configs.update_values.call_args[1]
        assert call_kwargs["description"] == "Bump temp"
        assert len(call_kwargs["values"]) == 1
        assert call_kwargs["values"][0]["key"] == "temperature"
        assert call_kwargs["values"][0]["value"] == "0.9"


class TestAssignEnvs:
    def test_assign_envs__multiple_envs__sends_correct_payload(
        self, config_client, mock_rest_client
    ):
        mock_rest_client.optimizer_configs.assign_envs.return_value = None

        config_client.assign_envs(
            config_id="cfg-1",
            blueprint_id="bp-1",
            envs=["prod", "staging"],
        )

        mock_rest_client.optimizer_configs.assign_envs.assert_called_once()
        call_kwargs = mock_rest_client.optimizer_configs.assign_envs.call_args[1]
        envs = call_kwargs["envs"]
        assert len(envs) == 2
        assert {"env": "prod", "blueprintId": "bp-1"} in envs
        assert {"env": "staging", "blueprintId": "bp-1"} in envs
