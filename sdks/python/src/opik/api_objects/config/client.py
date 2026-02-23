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
    config_id: str
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

        create_response = self._rest_client.optimizer_configs.create_config(
            project_name=project_name,
            blueprint=blueprint_payload,
        )

        config_id = create_response.id
        if config_id is None:
            raise ValueError("Backend returned no config_id")

        return self.get_blueprint(config_id=config_id)

    def get_blueprint(
        self,
        config_id: str,
        mask_id: typing.Optional[str] = None,
        env: typing.Optional[str] = None,
        field_types: typing.Optional[typing.Dict[str, typing.Any]] = None,
    ) -> ConfigData:
        """
        Fetch a blueprint from the backend and deserialize values.

        Args:
            config_id: The config ID.
            mask_id: Optional mask ID to pin a specific version.
            env: Optional env label to pin a specific version.
            field_types: Optional mapping of field_name -> py_type for deserialization.
        """
        try:
            blueprint = self._rest_client.optimizer_configs.get_blueprint(
                config_id,
                mask_id=mask_id,
                env=env,
            )
        except rest_api_core.ApiError as e:
            if e.status_code == 404:
                raise ValueError(f"Config not found: {config_id}") from e
            raise

        return self._blueprint_to_config_data(
            config_id=config_id,
            blueprint=blueprint,
            field_types=field_types,
        )

    def update_values(
        self,
        config_id: str,
        values: typing.Dict[str, typing.Tuple[typing.Any, typing.Any]],
        description: typing.Optional[str] = None,
        field_types: typing.Optional[typing.Dict[str, typing.Any]] = None,
    ) -> ConfigData:
        """
        Update config values (patch semantics — only specified keys are updated).

        Args:
            config_id: The config ID.
            values: Dict of field_name -> (py_type, value) to update.
            description: Optional description for the new blueprint.
            field_types: Optional mapping for deserializing the response.
        """
        backend_values = []
        for field_name, (py_type, value) in values.items():
            backend_values.append(
                {
                    "key": field_name,
                    "type": type_helpers.python_type_to_backend_type(py_type),
                    "value": type_helpers.python_value_to_backend_value(value, py_type),
                }
            )

        blueprint = self._rest_client.optimizer_configs.update_values(
            config_id,
            values=backend_values,
            description=description,
        )

        return self._blueprint_to_config_data(
            config_id=config_id,
            blueprint=blueprint,
            field_types=field_types,
        )

    def assign_envs(
        self,
        config_id: str,
        blueprint_id: str,
        envs: typing.List[str],
    ) -> None:
        env_payload = [
            {"env": env_label, "blueprintId": blueprint_id} for env_label in envs
        ]
        self._rest_client.optimizer_configs.assign_envs(
            config_id,
            envs=env_payload,
        )

    def _blueprint_to_config_data(
        self,
        config_id: str,
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
            config_id=config_id,
            blueprint_id=blueprint.id,
            values=values,
            description=blueprint.description,
        )
