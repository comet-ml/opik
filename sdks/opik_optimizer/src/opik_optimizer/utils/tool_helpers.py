from __future__ import annotations

from typing import Any, cast
import copy
import inspect

from ..api_objects import chat_prompt
from .. import helpers

__all__ = [
    "deep_merge_dicts",
    "serialize_tools",
    "describe_annotation",
    "summarize_tool_signatures",
]


def deep_merge_dicts(base: dict[str, Any], overrides: dict[str, Any]) -> dict[str, Any]:
    result = copy.deepcopy(base)
    for key, value in overrides.items():
        if key in result and isinstance(result[key], dict) and isinstance(value, dict):
            result[key] = deep_merge_dicts(result[key], value)
        else:
            result[key] = value
    return result


def serialize_tools(prompt: chat_prompt.ChatPrompt) -> list[dict[str, Any]]:
    tools_obj = getattr(prompt, "tools", None)
    if not isinstance(tools_obj, list):
        return []
    try:
        return copy.deepcopy(cast(list[dict[str, Any]], tools_obj))
    except Exception:  # pragma: no cover - defensive
        serialized_tools: list[dict[str, Any]] = []
        for tool in tools_obj:
            if isinstance(tool, dict):
                serialized_tools.append({k: v for k, v in tool.items() if k})
        return serialized_tools


def describe_annotation(annotation: Any) -> str | None:
    if annotation is inspect._empty:
        return None
    if isinstance(annotation, type):
        return annotation.__name__
    return str(annotation)


def summarize_tool_signatures(prompt: chat_prompt.ChatPrompt) -> list[dict[str, Any]]:
    signatures: list[dict[str, Any]] = []
    for name, func in getattr(prompt, "function_map", {}).items():
        callable_obj = getattr(func, "__wrapped__", func)
        try:
            sig = inspect.signature(callable_obj)
        except (TypeError, ValueError):  # pragma: no cover - defensive
            signatures.append({"name": name, "signature": "unavailable"})
            continue

        params: list[dict[str, Any]] = []
        for parameter in sig.parameters.values():
            params.append(
                helpers.drop_none(
                    {
                        "name": parameter.name,
                        "kind": parameter.kind.name,
                        "annotation": describe_annotation(parameter.annotation),
                        "default": (
                            None
                            if parameter.default is inspect._empty
                            else parameter.default
                        ),
                    }
                )
            )

        signatures.append(
            helpers.drop_none(
                {
                    "name": name,
                    "parameters": params,
                    "docstring": inspect.getdoc(callable_obj),
                }
            )
        )
    return signatures
