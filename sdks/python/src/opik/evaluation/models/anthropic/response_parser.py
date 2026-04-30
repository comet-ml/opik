"""Parse native Anthropic ``Message`` responses into ``ConversationDict`` shape."""

from __future__ import annotations

import json
from typing import List, Optional, TYPE_CHECKING, Union, cast

from opik import exceptions

from .. import base_model

if TYPE_CHECKING:
    from anthropic.types import Message, TextBlock, ToolUseBlock

    _ContentBlock = Union[TextBlock, ToolUseBlock]


def parse_assistant_message(response: "Message") -> base_model.ConversationDict:
    """Convert an Anthropic ``Message`` into an OpenAI-shape assistant turn.

    Text blocks are concatenated into ``content``. ``tool_use`` blocks are
    converted into ``tool_calls`` (function name + JSON-encoded arguments). When
    the caller used ``response_format`` and the model returned a single
    ``tool_use`` block with no text, its arguments are surfaced via ``content``
    so downstream JSON-parsing callers don't need to special-case structured
    output.
    """
    text_parts: List[str] = []
    tool_calls: List[base_model.ToolCall] = []

    blocks: List["_ContentBlock"] = list(getattr(response, "content", None) or [])
    for block in blocks:
        block_type: Optional[str] = getattr(block, "type", None)
        if block_type == "text":
            text: Optional[str] = getattr(block, "text", None)
            if text:
                text_parts.append(text)
        elif block_type == "tool_use":
            parsed_call = _parse_tool_use_block(block)
            if parsed_call is not None:
                tool_calls.append(parsed_call)

    content: Optional[str] = "".join(text_parts) if text_parts else None

    if content is None and len(tool_calls) == 1:
        # Anthropic's ``messages.parse`` (with ``output_format``) emits the
        # structured output as a tool_use block. Promote those arguments back
        # into ``content`` so callers using response_format keep working.
        content = tool_calls[0]["function"]["arguments"]
        tool_calls = []

    if content is None and not tool_calls:
        raise exceptions.BaseLLMError(
            "Received None as the output from the LLM. Please verify your environment "
            "configuration and ensure that the API keys for the models in use "
            "(e.g., ANTHROPIC_API_KEY) are set correctly."
        )

    assistant: base_model.ConversationDict = {"role": "assistant"}
    if content is not None:
        assistant["content"] = content
    if tool_calls:
        assistant["tool_calls"] = tool_calls
    return assistant


def _parse_tool_use_block(block: "ToolUseBlock") -> Optional[base_model.ToolCall]:
    block_id: Optional[str] = getattr(block, "id", None)
    name: Optional[str] = getattr(block, "name", None)
    block_input: Optional[object] = getattr(block, "input", None)
    if block_id is None or name is None:
        return None
    arguments: str = json.dumps(block_input if block_input is not None else {})
    return cast(
        base_model.ToolCall,
        {
            "id": block_id,
            "type": "function",
            "function": {"name": name, "arguments": arguments},
        },
    )
