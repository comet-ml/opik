import dataclasses
import typing
import logging

from opik.rest_api import client as rest_client
from opik.rest_api import core as rest_api_core
from opik.rest_api.types.optimizer_config_detail import (
    OptimizerConfigBlueprint,
)
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

    def create_config(
        self,
        fields_with_values: typing.Dict[str, typing.Tuple[typing.Any, typing.Any]],
        project_name: typing.Optional[str] = None,
        project_id: typing.Optional[str] = None,
        description: typing.Optional[str] = None,
    ) -> ConfigData:
        backend_values = []
        for field_name, (py_type, value) in fields_with_values.items():
            backend_values.append(
                {
                    "key": field_name,
                    "type": type_helpers.python_type_to_backend_type(py_type),
                    "value": type_helpers.python_value_to_backend_value(value, py_type),
                }
            )

        blueprint_payload: typing.Dict[str, typing.Any] = {
            "values": backend_values,
        }
        if description is not None:
            blueprint_payload["description"] = description

        self._rest_client.optimizer_configs.create_config(
            project_name=project_name,
            project_id=project_id,
            blueprint=blueprint_payload,
        )

        return self.get_blueprint(
            project_name=project_name,
        )

    def get_blueprint(
        self,
        project_name: typing.Optional[str] = None,
        env: typing.Optional[str] = None,
        mask_id: typing.Optional[str] = None,
        field_types: typing.Optional[typing.Dict[str, typing.Any]] = None,
    ) -> ConfigData:
        try:
            blueprint = self._rest_client.optimizer_configs.get_blueprint(
                project_name=project_name,
                env=env,
                mask_id=mask_id,
            )
        except rest_api_core.ApiError as e:
            if e.status_code == 404:
                raise ValueError("Config not found") from e
            raise

        return self._blueprint_to_config_data(
            blueprint=blueprint,
            field_types=field_types,
        )

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
