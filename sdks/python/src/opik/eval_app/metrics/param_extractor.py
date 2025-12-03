"""Helper functions for extracting parameter information from callables."""

import dataclasses
import inspect
import re
from typing import Any, Dict, List, Optional


@dataclasses.dataclass
class ParamInfo:
    """Information about a callable parameter."""

    name: str
    required: bool
    type: Optional[str] = None
    default: Optional[Any] = None
    description: Optional[str] = None


def extract_params(
    callable_obj: Any, param_descriptions: Optional[Dict[str, str]] = None
) -> List[ParamInfo]:
    """Extract parameter information from a callable's signature.

    Args:
        callable_obj: The callable to extract parameters from.
        param_descriptions: Optional dict mapping param names to descriptions.
    """
    sig = inspect.signature(callable_obj)
    params = []
    descriptions = param_descriptions or {}

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
            description=descriptions.get(name),
        )
        params.append(param_info)

    return params


def parse_docstring_args(docstring: Optional[str]) -> Dict[str, str]:
    """Parse the Args section of a docstring to extract parameter descriptions.

    Supports Google-style docstrings with Args: section.

    Args:
        docstring: The docstring to parse.

    Returns:
        A dict mapping parameter names to their descriptions.
    """
    if not docstring:
        return {}

    descriptions: Dict[str, str] = {}

    # Find Args section
    args_match = re.search(
        r"^\s*Args:\s*\n(.*?)(?=^\s*(?:Returns|Raises|Examples|Attributes|Note|See Also|Yields|Warning):|$)",
        docstring,
        re.MULTILINE | re.DOTALL,
    )

    if not args_match:
        return descriptions

    args_section = args_match.group(1)

    # Parse individual arguments
    # Pattern matches: "    param_name: description" or "    param_name (type): description"
    # Handles multi-line descriptions by looking for indented continuation lines
    lines = args_section.split("\n")
    current_param: Optional[str] = None
    current_desc_lines: List[str] = []

    for line in lines:
        # Check if this is a new parameter definition
        param_match = re.match(r"^\s{4,8}(\w+)(?:\s*\([^)]*\))?:\s*(.*)$", line)

        if param_match:
            # Save previous parameter if exists
            if current_param:
                descriptions[current_param] = " ".join(current_desc_lines).strip()

            current_param = param_match.group(1)
            desc = param_match.group(2).strip()
            current_desc_lines = [desc] if desc else []
        elif current_param and line.strip():
            # Continuation line - check if it's more indented than param definition
            if re.match(r"^\s{8,}", line):
                current_desc_lines.append(line.strip())

    # Don't forget the last parameter
    if current_param:
        descriptions[current_param] = " ".join(current_desc_lines).strip()

    return descriptions


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

