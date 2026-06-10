"""Parse native Anthropic ``Message`` responses into ``ConversationDict`` shape."""

from __future__ import annotations

import json
from typing import Iterable, List, Optional, Set, TYPE_CHECKING, Union, cast

from opik import exceptions

from .. import base_model

if TYPE_CHECKING:
    from anthropic.types import Message, TextBlock, ToolUseBlock

    _ContentBlock = Union[TextBlock, ToolUseBlock]


def parse_assistant_message(
    response: "Message",
    registered_tool_names: Optional[Iterable[str]] = None,
) -> base_model.ConversationDict:
    """Convert an Anthropic ``Message`` into an OpenAI-shape assistant turn.

    Text blocks are concatenated into ``content``. ``tool_use`` blocks are
    converted into ``tool_calls`` (function name + JSON-encoded arguments). When
    the caller used ``response_format`` and the model returned a single
    ``tool_use`` block with no text, its arguments are surfaced via ``content``
    so downstream JSON-parsing callers don't need to special-case structured
    output.

    Disambiguation between real tool calls and structured-output finalizer:
    Anthropic's ``messages.parse`` represents both as ``tool_use`` blocks.
    Without context the two are indistinguishable, and promoting a real
    tool call to ``content`` breaks the agentic loop (the loop sees no
    ``tool_calls``, can't execute the tool, and feeds the tool's *arguments*
    to the JSON parser as if they were the structured output). When the
    caller passes ``registered_tool_names``, any ``tool_use`` whose name
    matches a registered tool is kept as a real tool call; only unknown-
    named ``tool_use`` blocks (i.e. the structured-output finalizer) get
    promoted to ``content``.
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

    known_tool_names: Set[str] = (
        set(registered_tool_names) if registered_tool_names else set()
    )

    if content is None and len(tool_calls) == 1:
        only_call_name = tool_calls[0]["function"]["name"]
        # If the single tool_use names a real registered tool, leave it
        # as a tool call so the agentic loop can execute it. Otherwise
        # treat it as the structured-output finalizer.
        if only_call_name in known_tool_names:
            pass
        else:
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
