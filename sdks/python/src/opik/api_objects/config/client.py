import dataclasses
import typing
import logging

from opik.rest_api import client as rest_client
from opik.rest_api import core as rest_api_core
from opik.rest_api.types.optimizer_config_detail import (
    OptimizerConfigBlueprint,
)
from opik.api_objects import rest_helpers
from . import type_helpers

logger = logging.getLogger(__name__)


@dataclasses.dataclass
class ConfigData:
    blueprint_id: typing.Optional[str]
    values: typing.Dict[str, typing.Any]
    description: typing.Optional[str] = None


class ConfigClient:
    def __init__(self, client: rest_client.OpikApi):
        self._rest_client = client

    def _build_blueprint_payload(
        self,
        fields_with_values: typing.Dict[str, typing.Tuple[typing.Any, typing.Any]],
        description: typing.Optional[str],
    ) -> typing.Dict[str, typing.Any]:
        backend_values = []
        for field_name, (py_type, value) in fields_with_values.items():
            backend_values.append(
                {
                    "key": field_name,
                    "type": type_helpers.python_type_to_backend_type(py_type),
                    "value": type_helpers.python_value_to_backend_value(value, py_type),
                }
            )
        payload: typing.Dict[str, typing.Any] = {"values": backend_values}
        if description is not None:
            payload["description"] = description
        return payload

    def create_blueprint_only(
        self,
        fields_with_values: typing.Dict[str, typing.Tuple[typing.Any, typing.Any]],
        project_name: str,
        project_id: typing.Optional[str] = None,
        description: typing.Optional[str] = None,
    ) -> None:
        blueprint_payload = self._build_blueprint_payload(
            fields_with_values, description
        )
        self._rest_client.optimizer_configs.create_config(
            project_name=project_name,
            project_id=project_id,
            blueprint=blueprint_payload,
        )

    def create_config(
        self,
        fields_with_values: typing.Dict[str, typing.Tuple[typing.Any, typing.Any]],
        project_name: str,
        project_id: typing.Optional[str] = None,
        description: typing.Optional[str] = None,
    ) -> ConfigData:
        self.create_blueprint_only(
            fields_with_values=fields_with_values,
            project_name=project_name,
            project_id=project_id,
            description=description,
        )

        return self.get_blueprint(
            project_name=project_name,
        )

    def try_get_blueprint(
        self,
        project_name: str,
        env: typing.Optional[str] = None,
        mask_id: typing.Optional[str] = None,
        field_types: typing.Optional[typing.Dict[str, typing.Any]] = None,
    ) -> typing.Optional[ConfigData]:
        project_id = rest_helpers.resolve_project_id_by_name(
            self._rest_client, project_name
        )
        try:
            blueprint = self._rest_client.optimizer_configs.get_blueprint(
                project_id=project_id,
                env=env,
                mask_id=mask_id,
            )
        except rest_api_core.ApiError as e:
            if e.status_code == 404:
                return None
            raise

        return self._blueprint_to_config_data(
            blueprint=blueprint,
            field_types=field_types,
        )

    def get_blueprint(
        self,
        project_name: str,
        env: typing.Optional[str] = None,
        mask_id: typing.Optional[str] = None,
        field_types: typing.Optional[typing.Dict[str, typing.Any]] = None,
    ) -> ConfigData:
        result = self.try_get_blueprint(
            project_name=project_name,
            env=env,
            mask_id=mask_id,
            field_types=field_types,
        )
        if result is None:
            raise ValueError("Config not found")
        return result

    def _blueprint_to_config_data(
        self,
        blueprint: OptimizerConfigBlueprint,
        field_types: typing.Optional[typing.Dict[str, typing.Any]] = None,
    ) -> ConfigData:
        values: typing.Dict[str, typing.Any] = {}
        for param in blueprint.values or []:
            if field_types and param.key in field_types:
                py_type = field_types[param.key]
                values[param.key] = type_helpers.backend_value_to_python_value(
                    param.value, param.type, py_type
                )
            else:
                values[param.key] = param.value

        return ConfigData(
            blueprint_id=blueprint.id,
            values=values,
            description=blueprint.description,
        )
