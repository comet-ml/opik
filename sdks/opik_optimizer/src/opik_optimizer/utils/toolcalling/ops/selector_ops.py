"""Selector helpers for tool optimization entrypoints."""

from __future__ import annotations

from .... import constants
from ....api_objects import chat_prompt
from ....utils import prompt_segments
from ..normalize.tool_factory import ToolCallingFactory


def prepare_tool_optimization(
    prompt: chat_prompt.ChatPrompt,
    optimize_tools: bool | dict[str, bool] | None,
) -> tuple[chat_prompt.ChatPrompt, list[str] | None]:
    """Resolve MCP tools and return selected tool names for optimization."""
    if optimize_tools is None:
        return prompt, None

    resolved_prompt = ToolCallingFactory().resolve_prompt(prompt)
    if not resolved_prompt.tools:
        raise ValueError("Prompt must include tools for tool description optimization.")

    segments = prompt_segments.extract_prompt_segments(resolved_prompt)
    tool_segments = [segment for segment in segments if segment.is_tool()]
    if not tool_segments:
        raise ValueError("Prompt tools are missing tool descriptions to optimize.")

    tool_names: list[str] | None = None
    if isinstance(optimize_tools, dict):
        requested_names = [name for name, enabled in optimize_tools.items() if enabled]
        if not requested_names:
            raise ValueError("optimize_tools dict did not enable any tools.")
        available_segment_ids = [segment.segment_id for segment in tool_segments]
        tool_names = _resolve_requested_tool_names(
            requested_names=requested_names,
            available_segment_ids=available_segment_ids,
        )
    elif optimize_tools is True:
        tool_names = [
            segment.segment_id.replace(
                prompt_segments.PROMPT_SEGMENT_PREFIX_TOOL, "", 1
            )
            for segment in tool_segments
        ]
    # Limit the number of tools to optimize at once
    max_tools = constants.DEFAULT_TOOL_CALL_MAX_TOOLS_TO_OPTIMIZE
    if tool_names is not None and len(tool_names) > max_tools:
        raise ValueError(
            "optimize_tools supports at most "
            f"{max_tools} tools (requested {len(tool_names)}). "
            "Pass optimize_tools as a dict to select tools or reduce MCP tools."
        )
    return resolved_prompt, tool_names


def _resolve_requested_tool_names(
    *,
    requested_names: list[str],
    available_segment_ids: list[str],
) -> list[str]:
    """Resolve optimize_tools selectors to unique tool names."""
    available_lookup = {segment_id: segment_id for segment_id in available_segment_ids}
    suffix_lookup: dict[str, list[str]] = {}
    for segment_id in available_segment_ids:
        suffix = segment_id.rsplit(".", 1)[-1]
        suffix_lookup.setdefault(suffix, []).append(segment_id)

    resolved_names: list[str] = []
    missing_inputs: list[str] = []
    ambiguous_inputs: dict[str, list[str]] = {}
    for requested in requested_names:
        direct_match = available_lookup.get(requested)
        prefixed_match = available_lookup.get(
            f"{prompt_segments.PROMPT_SEGMENT_PREFIX_TOOL}{requested}"
        )
        suffix_matches = suffix_lookup.get(requested, [])
        suffix_match = suffix_matches[0] if len(suffix_matches) == 1 else None
        if direct_match is None and prefixed_match is None and len(suffix_matches) > 1:
            ambiguous_inputs[requested] = sorted(suffix_matches)
            continue
        resolved_segment = direct_match or prefixed_match or suffix_match
        if resolved_segment is None:
            missing_inputs.append(requested)
            continue
        resolved_tool_name = resolved_segment.replace(
            prompt_segments.PROMPT_SEGMENT_PREFIX_TOOL, "", 1
        )
        if resolved_tool_name not in resolved_names:
            resolved_names.append(resolved_tool_name)

    if ambiguous_inputs:
        raise ValueError(
            "Ambiguous optimize_tools entries: "
            f"{ambiguous_inputs}. Use full function names or prefixed segment IDs."
        )
    if missing_inputs:
        raise ValueError(
            "Tools not found in prompt for optimize_tools entries: "
            f"{missing_inputs}. Available segment IDs: {sorted(available_segment_ids)}"
        )
    return resolved_names
