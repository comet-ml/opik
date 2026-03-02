"""Helpers for applying tool description updates to prompts."""

from __future__ import annotations

from typing import Any

from ....utils import prompt_segments
from ....api_objects import chat_prompt

TOOL_COMPONENT_PREFIX = "__tool::"
TOOL_PARAM_COMPONENT_PREFIX = "__tool_param::"


def tool_component_key(prompt_name: str, tool_name: str) -> str:
    """Return the candidate key for a tool description component."""
    return f"{prompt_name}{TOOL_COMPONENT_PREFIX}{tool_name}"


def tool_param_component_key(prompt_name: str, tool_name: str, param_name: str) -> str:
    """Return the candidate key for a tool parameter description component."""
    return f"{prompt_name}{TOOL_PARAM_COMPONENT_PREFIX}{tool_name}::{param_name}"


def apply_tool_updates_from_candidate(
    *,
    candidate: dict[str, Any],
    prompt: chat_prompt.ChatPrompt,
    tool_component_prefix: str,
    tool_param_component_prefix: str,
) -> chat_prompt.ChatPrompt:
    """Apply tool/parameter description updates from a candidate map.

    The candidate contains flat component keys such as:
      - "{prompt_name}__tool::<tool_name>"
      - "{prompt_name}__tool_param::<tool_name>::<param_name>"
    """
    tool_updates: dict[str, str] = {}
    for key, value in candidate.items():
        if not isinstance(value, str):
            continue
        if key.startswith(tool_component_prefix):
            tool_name = key[len(tool_component_prefix) :]
            if tool_name:
                segment_id = f"{prompt_segments.PROMPT_SEGMENT_PREFIX_TOOL}{tool_name}"
                tool_updates[segment_id] = value
        elif key.startswith(tool_param_component_prefix):
            remainder = key[len(tool_param_component_prefix) :]
            if "::" not in remainder:
                continue
            tool_name, param_name = remainder.split("::", 1)
            if tool_name and param_name:
                segment_id = prompt_segments.tool_param_segment_id(
                    tool_name, param_name
                )
                tool_updates[segment_id] = value
    if not tool_updates:
        return prompt
    return prompt_segments.apply_segment_updates(prompt, tool_updates)


def apply_tool_updates_from_descriptions(
    *,
    prompt: chat_prompt.ChatPrompt,
    tool_descriptions: list[Any] | None,
    parameter_descriptions: list[Any] | None,
    allowed_tools: set[str] | None = None,
) -> chat_prompt.ChatPrompt:
    """Apply tool/parameter updates from structured description payloads."""
    tool_updates: dict[str, str] = {}
    for tool_update in tool_descriptions or []:
        name = getattr(tool_update, "name", None)
        description = getattr(tool_update, "description", None)
        if not isinstance(name, str) or not isinstance(description, str):
            continue
        if allowed_tools and name not in allowed_tools:
            continue
        segment_id = f"{prompt_segments.PROMPT_SEGMENT_PREFIX_TOOL}{name}"
        tool_updates[segment_id] = description
    for param_update in parameter_descriptions or []:
        tool_name = getattr(param_update, "tool_name", None)
        parameters = getattr(param_update, "parameters", None)
        if not isinstance(tool_name, str):
            continue
        if allowed_tools and tool_name not in allowed_tools:
            continue
        if not isinstance(parameters, list):
            continue
        for param in parameters:
            name = getattr(param, "name", None)
            description = getattr(param, "description", None)
            if not isinstance(name, str) or not isinstance(description, str):
                continue
            segment_id = prompt_segments.tool_param_segment_id(tool_name, name)
            tool_updates[segment_id] = description
    if not tool_updates:
        return prompt
    return prompt_segments.apply_segment_updates(prompt, tool_updates)


def build_tool_component_seed(
    *,
    prompt_name: str,
    prompt: chat_prompt.ChatPrompt,
    tool_names: list[str] | None = None,
) -> dict[str, str]:
    """Return tool component seed values for GEPA-style candidates."""
    seed: dict[str, str] = {}
    segments = prompt_segments.extract_prompt_segments(prompt)
    for segment in segments:
        if segment.is_tool():
            tool_name = segment.segment_id.replace(
                prompt_segments.PROMPT_SEGMENT_PREFIX_TOOL, "", 1
            )
            if tool_names and tool_name not in tool_names:
                continue
            seed[tool_component_key(prompt_name, tool_name)] = str(segment.content)
        elif segment.is_tool_param():
            param_tool_name = segment.metadata.get("tool_name")
            param_name = segment.metadata.get("param_name")
            if not isinstance(param_tool_name, str) or not isinstance(param_name, str):
                continue
            if tool_names and param_tool_name not in tool_names:
                continue
            seed[tool_param_component_key(prompt_name, param_tool_name, param_name)] = (
                str(segment.content)
            )
    return seed
