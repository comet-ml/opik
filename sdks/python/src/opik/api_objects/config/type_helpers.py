import dataclasses
import json
import typing

SUPPORTED_PRIMITIVE_TYPES = (str, int, float, bool)

_PYTHON_TO_BACKEND_TYPE: typing.Dict[type, str] = {
    str: "string",
    int: "number",
    float: "number",
    bool: "string",
}


def is_supported_type(py_type: typing.Any) -> bool:
    if py_type in SUPPORTED_PRIMITIVE_TYPES:
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
    if py_type in _PYTHON_TO_BACKEND_TYPE:
        return _PYTHON_TO_BACKEND_TYPE[py_type]
    origin = typing.get_origin(py_type)
    if origin in (list, dict):
        return "json"
    raise TypeError(f"Unsupported type: {py_type}")


def python_value_to_backend_value(value: typing.Any, py_type: typing.Any) -> str:
    if py_type is bool:
        return "true" if value else "false"
    if py_type in (int, float):
        return str(value)
    if py_type is str:
        return value
    origin = typing.get_origin(py_type)
    if origin in (list, dict):
        return json.dumps(value)
    raise TypeError(f"Unsupported type: {py_type}")


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

    origin = typing.get_origin(py_type)
    if origin in (list, dict):
        if isinstance(value, str):
            return json.loads(value)
        return value

    raise TypeError(f"Unsupported type: {py_type}")


def extract_dataclass_fields(
    cls: type,
) -> typing.List[typing.Tuple[str, typing.Any, typing.Any]]:
    """Returns (field_name, field_type, default_or_MISSING) for supported fields."""
    if not dataclasses.is_dataclass(cls):
        raise TypeError(f"{cls} is not a dataclass")

    type_hints = typing.get_type_hints(cls)
    result = []
    for f in dataclasses.fields(cls):
        py_type = type_hints.get(f.name, f.type)
        if not is_supported_type(py_type):
            continue
        if f.default is not dataclasses.MISSING:
            default = f.default
        elif f.default_factory is not dataclasses.MISSING:
            default = f.default_factory()
        else:
            default = dataclasses.MISSING
        result.append((f.name, py_type, default))
    return result
