import typing
import logging

from opik.rest_api import client as rest_client
from opik.rest_api import core as rest_api_core
from opik.rest_api.types.agent_blueprint_public import AgentBlueprintPublic
from opik.rest_api.types.agent_blueprint_write import AgentBlueprintWrite
from opik.rest_api.types.agent_config_value_write import AgentConfigValueWrite
from opik.rest_api.types.agent_config_env import AgentConfigEnv
from opik.api_objects import rest_helpers
from opik import id_helpers
from . import type_helpers

logger = logging.getLogger(__name__)


class ConfigClient:
    def __init__(self, client: rest_client.OpikApi):
        self._rest_client = client

    def _build_blueprint_payload(
        self,
        fields_with_values: typing.Dict[str, typing.Tuple[typing.Any, typing.Any]],
        description: typing.Optional[str],
        id: typing.Optional[str] = None,
        config_type: str = "blueprint",
    ) -> AgentBlueprintWrite:
        backend_values = []
        for field_name, (py_type, value) in fields_with_values.items():
            if (
                type_helpers.is_prompt_type(py_type)
                or type_helpers.is_prompt_version_type(py_type)
            ) and value is None:
                continue
            backend_values.append(
                AgentConfigValueWrite(
                    key=field_name,
                    type=type_helpers.python_type_to_backend_type(py_type),
                    value=type_helpers.python_value_to_backend_value(value, py_type),
                )
            )
        return AgentBlueprintWrite(
            id=id,
            type=config_type,
            values=backend_values,
            description=description,
        )

    def create_blueprint(
        self,
        fields_with_values: typing.Dict[str, typing.Tuple[typing.Any, typing.Any]],
        project_name: str,
        project_id: typing.Optional[str] = None,
        description: typing.Optional[str] = None,
    ) -> str:
        """Post a new blueprint to the backend, return the client-generated ID."""
        blueprint_id = id_helpers.generate_id()
        blueprint_payload = self._build_blueprint_payload(
            fields_with_values, description, id=blueprint_id
        )
        self._rest_client.agent_configs.create_agent_config(
            blueprint=blueprint_payload,
            project_name=project_name,
            project_id=project_id,
        )
        return blueprint_id

    def create_mask(
        self,
        fields_with_values: typing.Dict[str, typing.Tuple[typing.Any, typing.Any]],
        project_name: str,
        project_id: typing.Optional[str] = None,
        description: typing.Optional[str] = None,
    ) -> str:
        """Create a mask config, return the client-generated mask ID."""
        mask_id = id_helpers.generate_id()
        mask_payload = self._build_blueprint_payload(
            fields_with_values, description, id=mask_id, config_type="mask"
        )
        self._rest_client.agent_configs.create_agent_config(
            blueprint=mask_payload,
            project_name=project_name,
            project_id=project_id,
        )
        return mask_id

    def get_blueprint(
        self,
        project_name: str,
        env: typing.Optional[str] = None,
        mask_id: typing.Optional[str] = None,
    ) -> typing.Optional[AgentBlueprintPublic]:
        try:
            project_id = rest_helpers.resolve_project_id_by_name(
                self._rest_client, project_name
            )
            if env is not None:
                return self._rest_client.agent_configs.get_blueprint_by_env(
                    env_name=env,
                    project_id=project_id,
                    mask_id=mask_id,
                )
            else:
                return self._rest_client.agent_configs.get_latest_blueprint(
                    project_id=project_id,
                    mask_id=mask_id,
                )
        except rest_api_core.ApiError as e:
            if e.status_code == 404:
                return None
            raise

    def tag_blueprint_with_env(
        self,
        project_name: str,
        env: str,
        blueprint_id: str,
    ) -> None:
        project_id = rest_helpers.resolve_project_id_by_name(
            self._rest_client, project_name
        )
        self._rest_client.agent_configs.create_or_update_envs(
            project_id=project_id,
            envs=[AgentConfigEnv(env_name=env, blueprint_id=blueprint_id)],
        )
