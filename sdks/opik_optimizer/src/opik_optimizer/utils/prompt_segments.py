"""Prompt segmentation helpers for targeted prompt updates.

These utilities operate on existing ``ChatPrompt`` instances without
changing their constructor, allowing callers to identify and update
specific sections (system message, individual chat messages, or tool
descriptions) while preserving backwards compatibility for the rest of
the optimizer stack.
"""

from __future__ import annotations

from dataclasses import dataclass
from typing import Any
from collections.abc import Iterable

import copy

from ..api_objects import chat_prompt


PROMPT_SEGMENT_PREFIX_TOOL = "tool:"
PROMPT_SEGMENT_PREFIX_MESSAGE = "message:"


@dataclass
class PromptSegment:
    """Lightweight view over a prompt component that may be edited."""

    segment_id: str
    kind: str
    role: str | None
    content: str
    metadata: dict[str, Any]

    def is_tool(self) -> bool:
        return self.segment_id.startswith(PROMPT_SEGMENT_PREFIX_TOOL)


def _normalise_tool(tool: dict[str, Any]) -> dict[str, Any]:
    """Return tools in the ``{"function": {...}}`` structure for consistency."""

    if "function" in tool:
        return copy.deepcopy(tool)

    normalised = copy.deepcopy(tool)
    function_block = {
        "name": normalised.pop("name", None),
        "description": normalised.pop("description", ""),
        "parameters": normalised.pop("parameters", None),
    }
    normalised = {"function": function_block, **normalised}
    return normalised


def extract_prompt_segments(prompt: chat_prompt.ChatPrompt) -> list[PromptSegment]:
    """Extract individual editable segments from ``prompt``.

    The extraction preserves order for chat messages while assigning
    stable segment identifiers:

    * ``system`` for the system field (if present)
    * ``user`` for the top-level user field (if present)
    * ``message:<index>`` for entries in ``messages``
    * ``tool:<name>`` for tool descriptions
    """

    segments: list[PromptSegment] = []

    if prompt.system is not None:
        segments.append(
            PromptSegment(
                segment_id="system",
                kind="system",
                role="system",
                content=prompt.system,
                metadata={},
            )
        )

    if prompt.messages is not None:
        for idx, message in enumerate(prompt.messages):
            segments.append(
                PromptSegment(
                    segment_id=f"{PROMPT_SEGMENT_PREFIX_MESSAGE}{idx}",
                    kind="message",
                    role=message.get("role"),
                    content=message.get("content", ""),
                    metadata={
                        key: value for key, value in message.items() if key != "content"
                    },
                )
            )

    if prompt.user is not None:
        segments.append(
            PromptSegment(
                segment_id="user",
                kind="user",
                role="user",
                content=prompt.user,
                metadata={},
            )
        )

    if prompt.tools:
        for tool in prompt.tools:
            normalised = _normalise_tool(tool)
            function_block = normalised.get("function", {})
            tool_name = function_block.get("name")
            if not tool_name:
                continue
            segments.append(
                PromptSegment(
                    segment_id=f"{PROMPT_SEGMENT_PREFIX_TOOL}{tool_name}",
                    kind="tool",
                    role="tool",
                    content=function_block.get("description", ""),
                    metadata={
                        "parameters": function_block.get("parameters"),
                        "raw_tool": normalised,
                    },
                )
            )

    return segments


def apply_segment_updates(
    prompt: chat_prompt.ChatPrompt,
    updates: dict[str, str],
) -> chat_prompt.ChatPrompt:
    """Return a new ``ChatPrompt`` with selected segments replaced.

    ``updates`` maps segment identifiers (as produced by
    ``extract_prompt_segments``) to replacement strings.
    """

    system = updates.get("system", prompt.system)
    user = updates.get("user", prompt.user)

    messages: list[dict[str, Any]] | None = None
    if prompt.messages is not None:
        new_messages: list[dict[str, Any]] = []
        for idx, message in enumerate(prompt.messages):
            segment_id = f"{PROMPT_SEGMENT_PREFIX_MESSAGE}{idx}"
            replacement = updates.get(segment_id)
            if replacement is not None:
                updated_message = copy.deepcopy(message)
                updated_message["content"] = replacement
                new_messages.append(updated_message)
            else:
                new_messages.append(copy.deepcopy(message))
        messages = new_messages

    tools = copy.deepcopy(prompt.tools) if prompt.tools else None
    if tools:
        for tool in tools:
            normalised = _normalise_tool(tool)
            function_block = normalised.get("function", {})
            tool_name = function_block.get("name")
            if not tool_name:
                continue
            segment_id = f"{PROMPT_SEGMENT_PREFIX_TOOL}{tool_name}"
            replacement = updates.get(segment_id)
            if replacement is not None:
                function_block["description"] = replacement
            tool.update(normalised)

    return chat_prompt.ChatPrompt(
        name=prompt.name,
        system=system,
        user=user,
        messages=messages,
        tools=tools,
        function_map=prompt.function_map,
        model=prompt.model,
        invoke=prompt.invoke,
        model_parameters=prompt.model_kwargs,
    )


def segment_ids_for_tools(segments: Iterable[PromptSegment]) -> list[str]:
    """Convenience helper returning IDs of tool segments."""

    return [segment.segment_id for segment in segments if segment.is_tool()]
