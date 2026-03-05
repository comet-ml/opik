import copy
import datetime
import typing

from opik.rest_api import client as rest_client
from opik.rest_api.types.agent_blueprint_public import AgentBlueprintPublic
from opik.api_objects.prompt.text.prompt import Prompt
from opik.api_objects.prompt.chat.chat_prompt import ChatPrompt
from opik.rest_api.types.prompt_version_detail import PromptVersionDetail
from . import type_helpers


def _resolve_prompt_from_commit(
    rest_client_: rest_client.OpikApi, commit: str
) -> typing.Any:
    prompt_detail = rest_client_.prompts.get_prompt_by_commit(commit)
    version_detail = prompt_detail.requested_version
    if version_detail.template_structure == "chat":
        return ChatPrompt.from_fern_prompt_version(
            name=prompt_detail.name, prompt_version=version_detail
        )
    return Prompt.from_fern_prompt_version(
        name=prompt_detail.name, prompt_version=version_detail
    )


def _resolve_prompt_version_from_commit(
    rest_client_: rest_client.OpikApi, commit: str
) -> PromptVersionDetail:
    prompt_detail = rest_client_.prompts.get_prompt_by_commit(commit)
    return prompt_detail.requested_version


def _convert_primitives(
    raw_blueprint: AgentBlueprintPublic,
    field_types: typing.Optional[typing.Dict[str, typing.Any]],
) -> typing.Dict[str, typing.Any]:
    values: typing.Dict[str, typing.Any] = {}
    for param in raw_blueprint.values:
        if field_types and param.key in field_types:
            py_type = field_types[param.key]
        else:
            py_type = type_helpers.backend_type_to_python_type(param.type)

        if py_type is not None:
            values[param.key] = type_helpers.backend_value_to_python_value(
                param.value, param.type, py_type
            )
        else:
            values[param.key] = param.value
    return values


def _is_prompt_field(
    key: str,
    backend_type: str,
    field_types: typing.Optional[typing.Dict[str, typing.Any]],
) -> bool:
    if field_types and key in field_types:
        return type_helpers.is_prompt_type(field_types[key])
    return backend_type == "prompt"


def _is_prompt_version_field(
    key: str,
    backend_type: str,
    field_types: typing.Optional[typing.Dict[str, typing.Any]],
) -> bool:
    if field_types and key in field_types:
        return type_helpers.is_prompt_version_type(field_types[key])
    return backend_type == "prompt_commit"


def _resolve_prompts(
    raw_blueprint: AgentBlueprintPublic,
    values: typing.Dict[str, typing.Any],
    field_types: typing.Optional[typing.Dict[str, typing.Any]],
    rest_client_: rest_client.OpikApi,
) -> None:
    for param in raw_blueprint.values:
        raw_value = values.get(param.key)
        if not isinstance(raw_value, str):
            continue

        if _is_prompt_field(param.key, param.type, field_types):
            values[param.key] = _resolve_prompt_from_commit(rest_client_, raw_value)
        elif _is_prompt_version_field(param.key, param.type, field_types):
            values[param.key] = _resolve_prompt_version_from_commit(
                rest_client_, raw_value
            )


def _resolve_values(
    raw_blueprint: AgentBlueprintPublic,
    field_types: typing.Optional[typing.Dict[str, typing.Any]],
    rest_client_: typing.Optional[rest_client.OpikApi],
) -> typing.Dict[str, typing.Any]:
    values = _convert_primitives(raw_blueprint, field_types)
    if rest_client_:
        _resolve_prompts(raw_blueprint, values, field_types, rest_client_)
    return values


class Blueprint:
    """A specific versioned snapshot of agent config values (read-only)."""

    def __init__(
        self,
        raw_blueprint: AgentBlueprintPublic,
        field_types: typing.Optional[typing.Dict[str, typing.Any]] = None,
        rest_client_: typing.Optional[rest_client.OpikApi] = None,
    ) -> None:
        self._raw = raw_blueprint
        self._values = _resolve_values(raw_blueprint, field_types, rest_client_)

    @property
    def id(self) -> typing.Optional[str]:
        return self._raw.id

    @property
    def description(self) -> typing.Optional[str]:
        return self._raw.description

    @property
    def type(self) -> typing.Optional[str]:
        return self._raw.type

    @property
    def envs(self) -> typing.Optional[typing.List[str]]:
        return self._raw.envs

    @property
    def created_by(self) -> typing.Optional[str]:
        return self._raw.created_by

    @property
    def created_at(self) -> typing.Optional[datetime.datetime]:
        return self._raw.created_at

    @property
    def values(self) -> typing.Dict[str, typing.Any]:
        return copy.deepcopy(self._values)

    def get(self, key: str, default: typing.Any = None) -> typing.Any:
        return self._values.get(key, default)

    def __getitem__(self, key: str) -> typing.Any:
        return self._values[key]

    def keys(self) -> typing.KeysView[str]:
        return self._values.keys()
