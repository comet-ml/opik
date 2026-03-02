import typing

from opik.rest_api import client as rest_client
from opik.rest_api import core as rest_api_core
from opik.rest_api.types.agent_blueprint_write import AgentBlueprintWrite
from opik.rest_api.types.agent_config_value_write import AgentConfigValueWrite
from opik.rest_api.types.agent_config_env import AgentConfigEnv
from opik.api_objects import rest_helpers
from opik import id_helpers
from .blueprint import Blueprint
from . import type_helpers


class AgentConfig:
    """Project-level agent config entity."""

    def __init__(
        self,
        project_name: str,
        rest_client_: rest_client.OpikApi,
    ) -> None:
        self._project_name = project_name
        self._rest_client = rest_client_

    @property
    def project_name(self) -> str:
        return self._project_name

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

    def get_blueprint(
        self,
        *,
        id: typing.Optional[str] = None,
        env: typing.Optional[str] = None,
        mask_id: typing.Optional[str] = None,
        field_types: typing.Optional[typing.Dict[str, typing.Any]] = None,
    ) -> typing.Optional[Blueprint]:
        """Fetch a blueprint by ID, environment name, or latest.

        Priority: ``id`` > ``env`` > latest. Returns ``None`` if not found.

        Args:
            id: Fetch the blueprint with this exact ID.
            env: Fetch the blueprint tagged with this environment name.
            mask_id: ID of a mask blueprint to overlay on the result.
            field_types: Mapping of prefixed field key to Python type used
                for deserialising backend values.
        """
        try:
            if id is not None:
                raw = self._rest_client.agent_configs.get_blueprint_by_id(
                    id, mask_id=mask_id
                )
            else:
                project_id = rest_helpers.resolve_project_id_by_name(
                    self._rest_client, self._project_name
                )
                if env is not None:
                    raw = self._rest_client.agent_configs.get_blueprint_by_env(
                        env_name=env,
                        project_id=project_id,
                        mask_id=mask_id,
                    )
                else:
                    raw = self._rest_client.agent_configs.get_latest_blueprint(
                        project_id=project_id,
                        mask_id=mask_id,
                    )
        except rest_api_core.ApiError as e:
            if e.status_code == 404:
                return None
            raise
        return Blueprint(
            raw_blueprint=raw,
            field_types=field_types,
            rest_client_=self._rest_client,
        )

    def create_blueprint(
        self,
        parameters: typing.Optional[typing.Dict[str, typing.Any]] = None,
        fields_with_values: typing.Optional[
            typing.Dict[str, typing.Tuple[typing.Any, typing.Any]]
        ] = None,
        description: typing.Optional[str] = None,
        field_types: typing.Optional[typing.Dict[str, typing.Any]] = None,
    ) -> Blueprint:
        """Create a new blueprint and return it.

        Pass either ``parameters`` (plain key-value pairs whose types are
        inferred) or ``fields_with_values`` (explicit ``{key: (type, value)}``
        mapping). If both are given ``fields_with_values`` takes precedence.

        Args:
            parameters: Plain ``{field_name: value}`` dict; types are inferred
                via ``type(value)``.
            fields_with_values: Explicit ``{field_name: (python_type, value)}``
                mapping, bypassing type inference.
            description: Human-readable description stored with the blueprint.
            field_types: Mapping of prefixed field key to Python type used
                when fetching back the created blueprint.
        """
        if fields_with_values is None:
            fields_with_values = {
                k: (type(v), v) for k, v in (parameters or {}).items()
            }
        blueprint_id = id_helpers.generate_id()
        payload = self._build_blueprint_payload(
            fields_with_values, description, id=blueprint_id
        )
        self._rest_client.agent_configs.create_agent_config(
            blueprint=payload,
            project_name=self._project_name,
        )
        raw = self._rest_client.agent_configs.get_blueprint_by_id(blueprint_id)
        return Blueprint(
            raw_blueprint=raw,
            field_types=field_types,
            rest_client_=self._rest_client,
        )

    def tag_blueprint_with_env(self, env: str, blueprint_id: str) -> None:
        """Associate a blueprint with an environment name.

        Args:
            env: Environment name (e.g. ``"production"``).
            blueprint_id: ID of the blueprint to tag.
        """
        project_id = rest_helpers.resolve_project_id_by_name(
            self._rest_client, self._project_name
        )
        self._rest_client.agent_configs.create_or_update_envs(
            project_id=project_id,
            envs=[AgentConfigEnv(env_name=env, blueprint_id=blueprint_id)],
        )

    def create_mask(
        self,
        parameters: typing.Optional[typing.Dict[str, typing.Any]] = None,
        fields_with_values: typing.Optional[
            typing.Dict[str, typing.Tuple[typing.Any, typing.Any]]
        ] = None,
        description: typing.Optional[str] = None,
    ) -> str:
        """Create a mask blueprint and return its ID.

        A mask overlays a subset of fields on top of an existing blueprint.
        Apply it by passing the returned ID to ``get_blueprint(mask_id=...)``.

        Args:
            parameters: Plain ``{field_name: value}`` dict; types are inferred
                via ``type(value)``.
            fields_with_values: Explicit ``{field_name: (python_type, value)}``
                mapping, bypassing type inference.
            description: Human-readable description stored with the mask.
        """
        if fields_with_values is None:
            fields_with_values = {
                k: (type(v), v) for k, v in (parameters or {}).items()
            }
        mask_id = id_helpers.generate_id()
        payload = self._build_blueprint_payload(
            fields_with_values, description, id=mask_id, config_type="mask"
        )
        self._rest_client.agent_configs.create_agent_config(
            blueprint=payload,
            project_name=self._project_name,
        )
        return mask_id
