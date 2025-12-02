"""Parameter extraction utilities for metric introspection."""

import dataclasses
import inspect
from typing import Any, List, Optional


@dataclasses.dataclass
class ParamInfo:
    """Information about a function parameter."""

    name: str
    required: bool
    type: Optional[str] = None
    default: Optional[Any] = None


def extract_params(method: Any) -> List[ParamInfo]:
    """
    Extract parameter information from a method.

    Args:
        method: The method to extract parameters from.

    Returns:
        List of ParamInfo for each parameter.
    """
    sig = inspect.signature(method)

    params = []
    for name, param in sig.parameters.items():
        if name in ("self", "args", "kwargs", "ignored_kwargs"):
            continue

        param_info = _build_param_info(name, param)
        params.append(param_info)

    return params


def _build_param_info(name: str, param: inspect.Parameter) -> ParamInfo:
    """Build ParamInfo from a parameter."""
    param_type = None
    if param.annotation != inspect.Parameter.empty:
        param_type = _format_annotation(param.annotation)

    default = None
    if param.default != inspect.Parameter.empty:
        default = _serialize_default(param.default)

    return ParamInfo(
        name=name,
        required=param.default == inspect.Parameter.empty,
        type=param_type,
        default=default,
    )


def _format_annotation(annotation: Any) -> str:
    """Format a type annotation as a string."""
    if hasattr(annotation, "__origin__"):
        origin = getattr(annotation, "__origin__", None)
        args = getattr(annotation, "__args__", ())

        origin_name = getattr(origin, "__name__", str(origin))

        if origin_name == "Union":
            non_none_args = [a for a in args if a is not type(None)]
            if len(non_none_args) == 1 and type(None) in args:
                return f"Optional[{_format_annotation(non_none_args[0])}]"
            return f"Union[{', '.join(_format_annotation(a) for a in args)}]"

        if args:
            return f"{origin_name}[{', '.join(_format_annotation(a) for a in args)}]"
        return origin_name

    if hasattr(annotation, "__name__"):
        return annotation.__name__

    return str(annotation)


def _serialize_default(value: Any) -> Any:
    """Serialize a default value for JSON."""
    if value is None:
        return None
    if isinstance(value, (str, int, float, bool)):
        return value
    if isinstance(value, (list, tuple)):
        return [_serialize_default(v) for v in value]
    if isinstance(value, dict):
        return {k: _serialize_default(v) for k, v in value.items()}
    return str(value)
