import dataclasses
import json
import typing

from opik.api_objects.prompt.base_prompt import BasePrompt
from opik.rest_api.types.prompt_version_detail import PromptVersionDetail

SUPPORTED_PRIMITIVE_TYPES = (str, int, float, bool)

_PYTHON_TO_BACKEND_TYPE: typing.Dict[type, str] = {
    str: "string",
    int: "integer",
    float: "float",
    bool: "boolean",
}

_BACKEND_TO_PYTHON_TYPE: typing.Dict[str, type] = {
    v: k for k, v in _PYTHON_TO_BACKEND_TYPE.items()
}


def backend_type_to_python_type(backend_type: str) -> typing.Optional[type]:
    return _BACKEND_TO_PYTHON_TYPE.get(backend_type)


def unwrap_optional(py_type: typing.Any) -> typing.Optional[typing.Any]:
    """Return the inner type of Optional[T] (i.e. Union[T, None]), or None if not Optional."""
    if typing.get_origin(py_type) is typing.Union:
        args = [a for a in typing.get_args(py_type) if a is not type(None)]
        if len(args) == 1:
            return args[0]
    return None


def is_prompt_type(py_type: typing.Any) -> bool:
    return isinstance(py_type, type) and issubclass(py_type, BasePrompt)


def is_prompt_version_type(py_type: typing.Any) -> bool:
    return py_type is PromptVersionDetail


def is_supported_type(py_type: typing.Any) -> bool:
    inner = unwrap_optional(py_type)
    if inner is not None:
        return is_supported_type(inner)
    if py_type in SUPPORTED_PRIMITIVE_TYPES:
        return True
    if is_prompt_type(py_type):
        return True
    if is_prompt_version_type(py_type):
        return True
    origin = typing.get_origin(py_type)
    if origin is list:
        args = typing.get_args(py_type)
        return len(args) == 1 and args[0] in SUPPORTED_PRIMITIVE_TYPES
    if origin is dict:
        args = typing.get_args(py_type)
        return (
            len(args) == 2 and args[0] is str and args[1] in SUPPORTED_PRIMITIVE_TYPES
        )
    return False


def python_type_to_backend_type(py_type: typing.Any) -> str:
    inner = unwrap_optional(py_type)
    if inner is not None:
        return python_type_to_backend_type(inner)
    if py_type in _PYTHON_TO_BACKEND_TYPE:
        return _PYTHON_TO_BACKEND_TYPE[py_type]
    if is_prompt_type(py_type):
        return "prompt"
    if is_prompt_version_type(py_type):
        return "prompt_commit"
    origin = typing.get_origin(py_type)
    if origin in (list, dict):
        return "string"
    raise TypeError(f"Unsupported type: {py_type}")


def python_value_to_backend_value(value: typing.Any, py_type: typing.Any) -> str:
    if py_type is bool:
        return "true" if value else "false"
    if py_type in (int, float):
        return str(value)
    if py_type is str:
        return value
    if is_prompt_type(py_type):
        return value.commit
    if is_prompt_version_type(py_type):
        return value.commit
    origin = typing.get_origin(py_type)
    if origin in (list, dict):
        return json.dumps(value)
    raise TypeError(f"Unsupported type: {py_type}")


def python_value_to_metadata_value(
    value: typing.Any, py_type: typing.Any
) -> typing.Any:
    if is_prompt_type(py_type) or is_prompt_version_type(py_type):
        return python_value_to_backend_value(value, py_type)
    return value


def backend_value_to_python_value(
    value: typing.Any,
    backend_type: str,
    py_type: typing.Any,
) -> typing.Any:
    if value is None:
        return None

    if py_type is bool:
        if isinstance(value, bool):
            return value
        return str(value).lower() == "true"

    if py_type is int:
        return int(float(value)) if not isinstance(value, int) else value

    if py_type is float:
        return float(value) if not isinstance(value, float) else value

    if py_type is str:
        return str(value)

    if is_prompt_type(py_type):
        return str(value)

    if is_prompt_version_type(py_type):
        return str(value)

    origin = typing.get_origin(py_type)
    if origin in (list, dict):
        if isinstance(value, str):
            return json.loads(value)
        return value

    raise TypeError(f"Unsupported type: {py_type}")


def extract_dataclass_fields(
    cls: type,
) -> typing.List[typing.Tuple[str, typing.Any, typing.Optional[str]]]:
    """Returns (field_name, field_type, description) for supported fields."""
    if not dataclasses.is_dataclass(cls):
        raise TypeError(f"{cls} is not a dataclass")

    type_hints = typing.get_type_hints(cls, include_extras=True)
    result = []
    for f in dataclasses.fields(cls):
        raw_hint = type_hints.get(f.name, f.type)
        description: typing.Optional[str] = None
        if typing.get_origin(raw_hint) is typing.Annotated:
            args = typing.get_args(raw_hint)
            py_type = args[0]
            description = next((a for a in args[1:] if isinstance(a, str)), None)
        else:
            py_type = raw_hint
        inner = unwrap_optional(py_type)
        if inner is not None:
            py_type = inner
        if not is_supported_type(py_type):
            continue
        result.append((f.name, py_type, description))
    return result
