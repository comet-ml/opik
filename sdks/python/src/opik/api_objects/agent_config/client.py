import dataclasses
import typing
import logging

from opik.rest_api import client as rest_client
from opik.rest_api import core as rest_api_core
from opik.rest_api.types.optimizer_config_detail import (
    OptimizerConfigBlueprint,
)
from opik.api_objects import rest_helpers
from opik.api_objects.prompt.text.prompt import Prompt
from opik.api_objects.prompt.chat.chat_prompt import ChatPrompt
from . import type_helpers

logger = logging.getLogger(__name__)


def _resolve_prompt_from_version_id(
    rest_client_: rest_client.OpikApi, version_id: str
) -> typing.Any:
    version_detail = rest_client_.prompts.get_prompt_version_by_id(version_id)
    prompt_detail = rest_client_.prompts.get_prompt_by_id(version_detail.prompt_id)

    if version_detail.template_structure == "chat":
        return ChatPrompt.from_fern_prompt_version(
            name=prompt_detail.name, prompt_version=version_detail
        )
    return Prompt.from_fern_prompt_version(
        name=prompt_detail.name, prompt_version=version_detail
    )


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
        config_type: str = "blueprint",
    ) -> typing.Dict[str, typing.Any]:
        backend_values = []
        for field_name, (py_type, value) in fields_with_values.items():
            if type_helpers.is_prompt_type(py_type) and value is None:
                continue
            backend_values.append(
                {
                    "key": field_name,
                    "type": type_helpers.python_type_to_backend_type(py_type),
                    "value": type_helpers.python_value_to_backend_value(value, py_type),
                }
            )
        payload: typing.Dict[str, typing.Any] = {
            "type": config_type,
            "values": backend_values,
        }
        if description is not None:
            payload["description"] = description
        return payload

    def create_blueprint(
        self,
        fields_with_values: typing.Dict[str, typing.Tuple[typing.Any, typing.Any]],
        project_name: str,
        project_id: typing.Optional[str] = None,
        description: typing.Optional[str] = None,
    ) -> None:
        """Post a new blueprint to the backend without fetching it back.

        Use this when you need to write a blueprint but will retrieve the
        result separately (e.g. the decorator posts all classes' keys in
        separate calls, then does a single fetch).

        Args:
            fields_with_values: Mapping of field name → ``(python_type, value)``
                tuples to include in the blueprint.
            project_name: Name of the project the blueprint belongs to.
            project_id: Optional explicit project ID; forwarded to the backend.
            description: Optional human-readable description stored with the
                blueprint.
        """
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
        """Create a new blueprint on the backend and return its resolved data.

        Equivalent to calling :meth:`create_blueprint` followed by
        :meth:`get_blueprint`.

        Use :meth:`create_blueprint` directly when you need to write without
        an immediate fetch (e.g. the decorator writes each class's keys
        separately, then fetches once).

        Args:
            fields_with_values: Mapping of field name → ``(python_type, value)``
                tuples to include in the blueprint.
            project_name: Name of the project the blueprint belongs to.
            project_id: Optional explicit project ID; forwarded to the backend.
            description: Optional human-readable description stored with the
                blueprint.

        Returns:
            :class:`ConfigData` for the newly created blueprint.
        """
        self.create_blueprint(
            fields_with_values=fields_with_values,
            project_name=project_name,
            project_id=project_id,
            description=description,
        )

        return self.get_blueprint(
            project_name=project_name,
        )

    def create_mask(
        self,
        fields_with_values: typing.Dict[str, typing.Tuple[typing.Any, typing.Any]],
        project_name: str,
        project_id: typing.Optional[str] = None,
        description: typing.Optional[str] = None,
    ) -> ConfigData:
        """Create a mask config on the backend and return its data.

        Primarily for internal use. Works the same as :meth:`create_config` but
        sends the values payload under the ``"blueprint"`` key with ``type="mask"``.
        A mask typically covers only a subset of the
        blueprint's keys and is used to produce override variants (e.g. A/B
        experiment arms).

        Args:
            fields_with_values: Mapping of field name → ``(python_type, value)``
                tuples for the keys to include in the mask.
            project_name: Name of the project the mask belongs to.
            project_id: Optional explicit project ID; forwarded to the backend.
            description: Optional human-readable description stored with the mask.

        Returns:
            :class:`ConfigData` representing the blueprint that is active after
            the mask is applied.
        """
        mask_payload = self._build_blueprint_payload(
            fields_with_values, description, config_type="mask"
        )
        self._rest_client.optimizer_configs.create_config(
            project_name=project_name,
            project_id=project_id,
            blueprint=mask_payload,
        )

        return self.get_blueprint(
            project_name=project_name,
        )

    def _try_get_blueprint(
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
        """Retrieve an existing blueprint from the backend.

        Args:
            project_name: Name of the project whose blueprint to fetch.
            env: Return the blueprint pinned to this environment label.
            mask_id: Return the blueprint resolved through this mask ID.
            field_types: Optional mapping of field name → Python type used to
                deserialize backend values.  When provided, numeric strings are
                cast to ``float``/``int``, boolean strings to ``bool``, and
                prompt version IDs to the corresponding prompt object.

        Returns:
            :class:`ConfigData` populated with the blueprint's values.

        Raises:
            ValueError: If no blueprint exists for the given parameters.
        """
        result = self._try_get_blueprint(
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

        for key, raw_value in list(values.items()):
            if field_types and key in field_types:
                py_type = field_types[key]
                if type_helpers.is_prompt_type(py_type) and isinstance(raw_value, str):
                    try:
                        values[key] = _resolve_prompt_from_version_id(
                            self._rest_client, raw_value
                        )
                    except Exception:
                        logger.debug(
                            "Failed to resolve prompt version %s",
                            raw_value,
                            exc_info=True,
                        )
                        del values[key]

        return ConfigData(
            blueprint_id=blueprint.id,
            values=values,
            description=blueprint.description,
        )
