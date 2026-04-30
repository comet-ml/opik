"""Parse Langchain ``AIMessage`` objects into ``ConversationDict`` shape."""

from __future__ import annotations

import json
from typing import Any, Dict, List, Optional, TYPE_CHECKING, Union, cast

from opik import exceptions

from .. import base_model

if TYPE_CHECKING:
    from langchain_core.messages import AIMessage
    from langchain_core.messages.tool import ToolCall as LangchainToolCall


_ContentBlock = Dict[str, Any]
_LangchainContent = Union[str, List[_ContentBlock]]


def parse_assistant_message(response: "AIMessage") -> base_model.ConversationDict:
    """Convert a Langchain ``AIMessage`` into an OpenAI-shape assistant turn.

    Langchain returns text in ``AIMessage.content`` (string or block list) and
    structured tool calls in ``AIMessage.tool_calls``. We surface both: text
    becomes ``content``, tool calls become OpenAI-shape ``tool_calls`` with the
    args JSON-encoded.
    """
    raw_content: Optional[_LangchainContent] = getattr(response, "content", None)
    content: Optional[str] = _flatten_content(raw_content)
    tool_calls: List[base_model.ToolCall] = _extract_tool_calls(response)

    if content is None and not tool_calls:
        raise exceptions.BaseLLMError(
            "Received None as the output from the LLM. Please verify your environment "
            "configuration and ensure that the API keys for the models in use are set correctly."
        )

    assistant: base_model.ConversationDict = {"role": "assistant"}
    if content is not None:
        assistant["content"] = content
    if tool_calls:
        assistant["tool_calls"] = tool_calls
    return assistant


def _flatten_content(content: Optional[_LangchainContent]) -> Optional[str]:
    if content is None:
        return None
    if isinstance(content, str):
        return content
    if isinstance(content, list):
        # Langchain's content-block format: collect ``text`` parts only and
        # discard tool/image blocks (those are surfaced via ``tool_calls``).
        text_parts: List[str] = [
            str(block.get("text", ""))
            for block in content
            if isinstance(block, dict) and block.get("type") == "text"
        ]
        joined: str = "".join(text_parts)
        return joined if joined else None
    raise exceptions.BaseLLMError("LLM choice contains non-text content")


def _extract_tool_calls(response: "AIMessage") -> List[base_model.ToolCall]:
    raw_tool_calls: List["LangchainToolCall"] = list(
        getattr(response, "tool_calls", None) or []
    )
    parsed: List[base_model.ToolCall] = []
    for raw in raw_tool_calls:
        name: Optional[str] = _get_str(raw, "name")
        call_id: Optional[str] = _get_str(raw, "id")
        args: Any = _get(raw, "args")
        if not name or call_id is None:
            continue
        if isinstance(args, str):
            arguments: str = args
        else:
            arguments = json.dumps(args if args is not None else {})
        parsed.append(
            cast(
                base_model.ToolCall,
                {
                    "id": call_id,
                    "type": "function",
                    "function": {"name": name, "arguments": arguments},
                },
            )
        )
    return parsed


def _get(value: Any, key: str) -> Any:
    if isinstance(value, dict):
        return value.get(key)
    return getattr(value, key, None)


def _get_str(value: Any, key: str) -> Optional[str]:
    raw = _get(value, key)
    if raw is None:
        return None
    return str(raw)
