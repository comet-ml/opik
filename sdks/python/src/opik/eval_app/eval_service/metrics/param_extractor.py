"""Helper functions for extracting parameter information from callables."""

import dataclasses
import inspect
import typing
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
    """Format a type annotation as a string, preserving the full Python annotation."""
    # Handle None type
    if annotation is type(None):
        return "None"

    # Handle generic types FIRST (Optional, Union, List, Dict, etc.)
    # These have both __origin__ and __name__, so check __origin__ first
    if hasattr(annotation, "__origin__"):
        origin = annotation.__origin__
        args = getattr(annotation, "__args__", ())

        # Get the origin name
        if origin is typing.Union:
            # Filter out NoneType to check if it's Optional
            non_none_args = [a for a in args if a is not type(None)]
            has_none = len(non_none_args) < len(args)

            if has_none and len(non_none_args) == 1:
                # It's Optional[X] - Union with exactly one non-None type
                return f"Optional[{_format_annotation(non_none_args[0])}]"
            elif has_none:
                # It's Union[X, Y, ..., None] - format as Optional[Union[X, Y, ...]]
                inner_args = ", ".join(_format_annotation(a) for a in non_none_args)
                return f"Optional[Union[{inner_args}]]"
            else:
                # Pure Union without None
                args_str = ", ".join(_format_annotation(a) for a in args)
                return f"Union[{args_str}]"
        elif hasattr(origin, "__name__"):
            origin_name = origin.__name__
        else:
            # Fallback for other origins (e.g., list, dict in Python 3.9+)
            origin_str = str(origin)
            if "." in origin_str:
                origin_name = origin_str.split(".")[-1]
            else:
                origin_name = origin_str

        if args:
            args_str = ", ".join(_format_annotation(a) for a in args)
            return f"{origin_name}[{args_str}]"
        return origin_name

    # Handle simple types with __name__ (int, str, bool, classes, etc.)
    if hasattr(annotation, "__name__"):
        # Use just the class name, not the full module path
        return annotation.__name__

    # Handle string annotations or other cases
    result = str(annotation)
    # Clean up typing module prefix and full module paths
    result = result.replace("typing.", "")
    # Extract just the class name from full module paths
    if "." in result and not result.startswith("["):
        parts = result.split(".")
        result = parts[-1]
    return result


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
