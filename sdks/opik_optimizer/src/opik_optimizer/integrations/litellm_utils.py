from typing import Callable, Dict, Any, Optional, List

import inspect
import typing


def function_to_litellm_definition(
    func: Callable, description: Optional[str] = None
) -> Dict[str, Any]:
    sig = inspect.signature(func)
    doc = description or func.__doc__ or ""

    properties: Dict[str, Dict[str, str]] = {}
    required: List[str] = []

    for name, param in sig.parameters.items():
        param_type = (
            param.annotation if param.annotation != inspect.Parameter.empty else str
        )
        json_type = python_type_to_json_type(param_type)
        properties[name] = {"type": json_type, "description": f"{name} parameter"}
        if param.default == inspect.Parameter.empty:
            required.append(name)

    return {
        "type": "function",
        "function": {
            "name": func.__name__,
            "description": doc.strip(),
            "parameters": {
                "type": "object",
                "properties": properties,
                "required": required,
            },
        },
    }


def python_type_to_json_type(python_type: type) -> str:
    # Basic type mapping
    if python_type in [str]:
        return "string"
    elif python_type in [int]:
        return "integer"
    elif python_type in [float]:
        return "number"
    elif python_type in [bool]:
        return "boolean"
    elif python_type in [dict]:
        return "object"
    elif python_type in [list, typing.List]:
        return "array"
    else:
        return "string"  # default fallback
