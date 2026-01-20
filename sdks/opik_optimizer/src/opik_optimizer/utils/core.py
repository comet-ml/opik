from __future__ import annotations

from typing import Any, cast
import copy

from ..api_objects import chat_prompt

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
    tools = getattr(prompt, "tools", None)
    if not tools:
        return []
    serialized: list[dict[str, Any]] = []
    for tool in tools:
        if isinstance(tool, dict):
            serialized.append(tool)
        else:
            serialized.append(tool.model_dump())
    return serialized


def describe_annotation(annotation: Any) -> str | None:
    if annotation is None:
        return None
    if isinstance(annotation, type):
        return annotation.__name__
    if hasattr(annotation, "__name__"):
        return annotation.__name__
    return str(annotation)


def summarize_tool_signatures(prompt: chat_prompt.ChatPrompt) -> list[dict[str, Any]]:
    tools = getattr(prompt, "tools", None)
    if not tools:
        return []

    summarized: list[dict[str, Any]] = []
    for tool in tools:
        if isinstance(tool, dict):
            tool_dict = tool
        else:
            tool_dict = cast(dict[str, Any], tool.model_dump())
        function_info = tool_dict.get("function", {})
        params = function_info.get("parameters", {})
        summarized.append(
            {
                "name": function_info.get("name"),
                "description": function_info.get("description"),
                "parameters": params,
                "returns": describe_annotation(function_info.get("returns")),
            }
        )

    return summarized
