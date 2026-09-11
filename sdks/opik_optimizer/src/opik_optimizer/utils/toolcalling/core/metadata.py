"""Shared helpers for tool metadata extraction."""

from __future__ import annotations

import json
from typing import Any

from ....api_objects.chat_prompt import ChatPrompt
from ....utils import prompt_segments
from ..normalize.tool_factory import ToolCallingFactory
from . import segment_updates


def build_tool_metadata_by_component(
    *,
    base_prompts: dict[str, Any],
) -> dict[str, str]:
    """Build tool metadata keyed by candidate component IDs."""
    tool_metadata_by_component: dict[str, str] = {}
    for prompt_name, prompt in base_prompts.items():
        segments = prompt_segments.extract_prompt_segments(prompt)
        for segment in segments:
            if segment.is_tool():
                tool_name = segment.segment_id.replace(
                    prompt_segments.PROMPT_SEGMENT_PREFIX_TOOL, "", 1
                )
                key = segment_updates.tool_component_key(prompt_name, tool_name)
                tool_metadata_by_component[key] = json.dumps(
                    segment.metadata.get("raw_tool", {}),
                    sort_keys=True,
                    default=str,
                )
            elif segment.is_tool_param():
                param_tool_name = segment.metadata.get("tool_name")
                param_name = segment.metadata.get("param_name")
                if not isinstance(param_tool_name, str) or not isinstance(
                    param_name, str
                ):
                    continue
                key = segment_updates.tool_param_component_key(
                    prompt_name, param_tool_name, param_name
                )
                tool_metadata_by_component[key] = json.dumps(
                    segment.metadata.get("param_schema", {}),
                    sort_keys=True,
                    default=str,
                )
    return tool_metadata_by_component


def extract_tool_descriptions(prompt: ChatPrompt) -> dict[str, str]:
    """Return function name -> description for resolved tool entries."""
    resolved_prompt = ToolCallingFactory().resolve_prompt(prompt)
    descriptions: dict[str, str] = {}
    for tool in resolved_prompt.tools or []:
        function = tool.get("function", {})
        name = function.get("name")
        if isinstance(name, str):
            descriptions[name] = str(function.get("description", ""))
    return descriptions
