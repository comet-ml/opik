from unittest import mock

import pytest

from opik.api_objects.config.client import ConfigClient, ConfigData
from opik.rest_api.types.optimizer_config_detail import (
    OptimizerConfigBlueprint,
    OptimizerConfigCreateResponse,
    OptimizerConfigParameter,
)


def _make_create_response():
    return OptimizerConfigCreateResponse(id="cfg-123")


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
            _make_create_response()
        )
        mock_rest_client.optimizer_configs.get_blueprint.return_value = (
            _make_blueprint()
        )

        result = config_client.create_config(
            fields_with_values={
                "temperature": (float, 0.6),
                "name": (str, "agent"),
            },
        )

        mock_rest_client.optimizer_configs.create_config.assert_called_once()
        call_kwargs = mock_rest_client.optimizer_configs.create_config.call_args[1]
        assert call_kwargs["blueprint"]["values"] is not None

        mock_rest_client.optimizer_configs.get_blueprint.assert_called_once()

        assert isinstance(result, ConfigData)
        assert result.blueprint_id == "bp-456"

    def test_create__bool_field__serialized_as_string_type(
        self, config_client, mock_rest_client
    ):
        mock_rest_client.optimizer_configs.create_config.return_value = (
            _make_create_response()
        )
        mock_rest_client.optimizer_configs.get_blueprint.return_value = (
            _make_blueprint()
        )

        config_client.create_config(
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
            fields_with_values={"temp": (float, 0.5)},
            project_name="my-project",
        )

        call_kwargs = mock_rest_client.optimizer_configs.create_config.call_args[1]
        assert call_kwargs["project_name"] == "my-project"

    def test_create__with_project_name__get_blueprint_uses_same_project(
        self, config_client, mock_rest_client
    ):
        mock_rest_client.optimizer_configs.create_config.return_value = (
            _make_create_response()
        )
        mock_rest_client.optimizer_configs.get_blueprint.return_value = (
            _make_blueprint()
        )

        config_client.create_config(
            fields_with_values={"temp": (float, 0.5)},
            project_name="my-project",
        )

        get_bp_kwargs = mock_rest_client.optimizer_configs.get_blueprint.call_args[1]
        assert get_bp_kwargs.get("project_name") == "my-project"

    def test_create__with_project_id__passes_project_id_to_backend(
        self, config_client, mock_rest_client
    ):
        mock_rest_client.optimizer_configs.create_config.return_value = (
            _make_create_response()
        )
        mock_rest_client.optimizer_configs.get_blueprint.return_value = (
            _make_blueprint()
        )

        config_client.create_config(
            fields_with_values={"temp": (float, 0.5)},
            project_id="proj-99",
        )

        call_kwargs = mock_rest_client.optimizer_configs.create_config.call_args[1]
        assert call_kwargs["project_id"] == "proj-99"


class TestGetBlueprint:
    def test_get_blueprint__happy_path__returns_config_data(
        self, config_client, mock_rest_client
    ):
        mock_rest_client.optimizer_configs.get_blueprint.return_value = _make_blueprint(
            blueprint_id="bp-789"
        )

        result = config_client.get_blueprint(project_name="my-project")

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
            project_name="my-project",
            field_types={"temperature": float, "flag": bool},
        )

        assert result.values["temperature"] == 0.6
        assert result.values["flag"] is True

    @pytest.mark.parametrize(
        "mask_id",
        ["mask-1", "mask-2", None],
        ids=["mask_1", "mask_2", "no_mask"],
    )
    def test_get_blueprint__mask_id__passed_to_backend(
        self, config_client, mock_rest_client, mask_id
    ):
        mock_rest_client.optimizer_configs.get_blueprint.return_value = (
            _make_blueprint()
        )

        config_client.get_blueprint(
            project_name="my-project",
            mask_id=mask_id,
        )

        mock_rest_client.optimizer_configs.get_blueprint.assert_called_once_with(
            project_name="my-project",
            env=None,
            mask_id=mask_id,
        )
