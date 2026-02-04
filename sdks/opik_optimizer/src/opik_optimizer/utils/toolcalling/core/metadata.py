"""Shared helpers for tool metadata extraction."""

from __future__ import annotations

import json
from typing import Any

from ....utils import prompt_segments
from . import components


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
                key = components.tool_component_key(prompt_name, tool_name)
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
                key = components.tool_param_component_key(
                    prompt_name, param_tool_name, param_name
                )
                tool_metadata_by_component[key] = json.dumps(
                    segment.metadata.get("param_schema", {}),
                    sort_keys=True,
                    default=str,
                )
    return tool_metadata_by_component
