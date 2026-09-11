"""Rendering/reporting helpers for tool optimization prompts."""

from __future__ import annotations

import json
from typing import Any
from collections.abc import Callable

from ....api_objects import chat_prompt
from ....utils import prompt_segments

_SENSITIVE_METADATA_KEYS = (
    "mcp",
    "server",
    "authorization",
    "auth",
    "headers",
    "env",
)

ToolDescriptionReporter = Callable[[str, str, dict[str, Any]], None]


def build_tool_blocks_from_segments(
    segments: list[prompt_segments.PromptSegment],
) -> str:
    """Return formatted tool blocks for a list of tool segments."""
    blocks: list[str] = []
    for segment in segments:
        tool_name = segment.segment_id.replace(
            prompt_segments.PROMPT_SEGMENT_PREFIX_TOOL, "", 1
        )
        tool_metadata = _sanitize_tool_metadata(segment.metadata.get("raw_tool", {}))
        tool_metadata_json = json.dumps(
            tool_metadata, indent=2, sort_keys=True, default=str
        )
        block = (
            f"Tool name: {tool_name}\n"
            f"Tool description:\n{segment.content}\n"
            f"Tool metadata (JSON):\n{tool_metadata_json}"
        )
        blocks.append(block)
    return "\n\n".join(blocks)


def build_tool_blocks_from_prompt(
    prompt: chat_prompt.ChatPrompt,
    tool_names: list[str] | None = None,
) -> str:
    """Return formatted tool blocks for a prompt."""
    segments = prompt_segments.extract_prompt_segments(prompt)
    tool_segments = [segment for segment in segments if segment.is_tool()]
    if tool_names:
        allowed = {
            f"{prompt_segments.PROMPT_SEGMENT_PREFIX_TOOL}{name}" for name in tool_names
        }
        tool_segments = [
            segment for segment in tool_segments if segment.segment_id in allowed
        ]
    if not tool_segments:
        return ""
    return build_tool_blocks_from_segments(tool_segments)


def report_tool_descriptions(
    prompt: chat_prompt.ChatPrompt,
    tool_names: list[str] | None,
    reporter: ToolDescriptionReporter,
) -> None:
    """Report tool descriptions using a caller-provided reporter."""
    segments = prompt_segments.extract_prompt_segments(prompt)
    tool_segments = [segment for segment in segments if segment.is_tool()]
    if tool_names:
        allowed = {
            f"{prompt_segments.PROMPT_SEGMENT_PREFIX_TOOL}{name}" for name in tool_names
        }
        tool_segments = [
            segment for segment in tool_segments if segment.segment_id in allowed
        ]
    for segment in tool_segments:
        tool_name = segment.segment_id.replace(
            prompt_segments.PROMPT_SEGMENT_PREFIX_TOOL, "", 1
        )
        reporter(segment.content, tool_name, segment.metadata)


def make_tool_description_reporter(
    display_fn: Callable[[str, str], None],
) -> ToolDescriptionReporter:
    """Return a reporter that appends tool parameters before display."""

    def _reporter(
        description: str,
        name: str,
        metadata: dict[str, Any],
    ) -> None:
        """Format tool descriptions and parameters for display."""
        signature = ""
        raw_tool = metadata.get("raw_tool") or {}
        parameters = (
            raw_tool.get("function", {}).get("parameters")
            if isinstance(raw_tool, dict)
            else None
        )
        if parameters:
            signature = "\n\nTool parameters:\n" + json.dumps(
                parameters,
                indent=2,
                sort_keys=True,
                default=str,
            )
        text = f"{description}{signature}"
        display_fn(text, name)

    return _reporter


def _sanitize_tool_metadata(raw_tool: Any) -> Any:
    """Remove sensitive MCP/server/auth metadata before sending to model prompts."""
    if isinstance(raw_tool, dict):
        sanitized: dict[str, Any] = {}
        for key, value in raw_tool.items():
            key_text = str(key)
            if any(secret in key_text.lower() for secret in _SENSITIVE_METADATA_KEYS):
                sanitized[key_text] = "***REDACTED***"
                continue
            sanitized[key_text] = _sanitize_tool_metadata(value)
        return sanitized
    if isinstance(raw_tool, list):
        return [_sanitize_tool_metadata(item) for item in raw_tool]
    return raw_tool
