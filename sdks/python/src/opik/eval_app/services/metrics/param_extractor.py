"""Helper functions for extracting parameter information from callables."""

import dataclasses
import inspect
from typing import Any, List, Optional


@dataclasses.dataclass
class ParamInfo:
    """Information about a callable parameter."""

    name: str
    required: bool
    type: Optional[str] = None
    default: Optional[Any] = None


def extract_params(callable_obj: Any) -> List[ParamInfo]:
    """Extract parameter information from a callable's signature using annotations."""
    sig = inspect.signature(callable_obj)
    params = []

    for name, param in sig.parameters.items():
        if name in ("self", "args", "kwargs", "ignored_kwargs"):
            continue

        param_info = ParamInfo(
            name=name,
            required=param.default == inspect.Parameter.empty,
            type=(
                _format_annotation(param.annotation)
                if param.annotation != inspect.Parameter.empty
                else None
            ),
            default=(
                _serialize_default(param.default)
                if param.default != inspect.Parameter.empty
                else None
            ),
        )
        params.append(param_info)

    return params


def _format_annotation(annotation: Any) -> str:
    """Format a type annotation as a string."""
    if hasattr(annotation, "__name__"):
        return annotation.__name__
    if hasattr(annotation, "__origin__"):
        # Handle generic types like Optional, List, etc.
        origin = annotation.__origin__
        args = getattr(annotation, "__args__", ())
        if origin is type(None):
            return "None"
        origin_name = getattr(origin, "__name__", str(origin))
        if args:
            args_str = ", ".join(_format_annotation(a) for a in args)
            return f"{origin_name}[{args_str}]"
        return origin_name
    return str(annotation)


def _serialize_default(value: Any) -> Any:
    """Serialize a default value for JSON output."""
    if value is None:
        return None
    if isinstance(value, (str, int, float, bool)):
        return value
    if isinstance(value, (list, tuple)):
        return [_serialize_default(v) for v in value]
    if isinstance(value, dict):
        return {k: _serialize_default(v) for k, v in value.items()}
    # For complex objects, return their string representation
    return str(value)
