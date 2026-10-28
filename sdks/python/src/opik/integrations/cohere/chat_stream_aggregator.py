import logging
from typing import Any, Dict, List, Optional

import pydantic

LOGGER = logging.getLogger(__name__)


class ChatStreamAggregated(pydantic.BaseModel):
    id: Optional[str]
    finish_reason: Optional[str]
    message: Dict[str, Any]
    usage: Optional[Dict[str, Any]]


def aggregate(items: List[Any]) -> Optional[ChatStreamAggregated]:
    """Fold Cohere's chat-stream events back into a chat()-shaped result.

    Cohere streams a discriminated union of events rather than deltas on a
    single object, so the text arrives via `content-delta` while the id,
    finish_reason and usage arrive on `message-start` and `message-end`.
    A turn that ends in tool calls carries no text at all: the plan arrives
    via `tool-plan-delta` and the calls via `tool-call-start` /
    `tool-call-delta` / `tool-call-end`, keyed by the event `index`.
    """
    try:
        message_id: Optional[str] = None
        finish_reason: Optional[str] = None
        usage: Optional[Dict[str, Any]] = None
        text_chunks: List[str] = []
        thinking_chunks: List[str] = []
        tool_plan_chunks: List[str] = []
        tool_calls: Dict[int, Dict[str, Any]] = {}

        for event in items:
            event_type = getattr(event, "type", None)

            if event_type == "message-start":
                message_id = getattr(event, "id", None)

            elif event_type == "content-delta":
                content = _content_of(event)
                if content is None:
                    continue
                if content.text:
                    text_chunks.append(content.text)
                # Cohere carries reasoning in `thinking`, beside `text`. Without
                # this a reasoning model's span would show an empty answer.
                if content.thinking:
                    thinking_chunks.append(content.thinking)

            elif event_type == "tool-plan-delta":
                tool_plan = _tool_plan_of(event)
                if tool_plan:
                    tool_plan_chunks.append(tool_plan)

            elif event_type == "tool-call-start":
                _start_tool_call(tool_calls, event)

            elif event_type == "tool-call-delta":
                _append_tool_call_arguments(tool_calls, event)

            elif event_type == "tool-call-end":
                _finish_tool_call(tool_calls, event)

            elif event_type == "message-end":
                delta = getattr(event, "delta", None)
                if delta is not None:
                    finish_reason = delta.finish_reason
                    if delta.usage is not None:
                        usage = delta.usage.model_dump()

        message: Dict[str, Any] = {
            "role": "assistant",
            "content": [{"type": "text", "text": "".join(text_chunks)}],
        }
        if thinking_chunks:
            message["content"].insert(
                0, {"type": "thinking", "thinking": "".join(thinking_chunks)}
            )
        # Match the non-streamed chat() shape: the keys appear only when the
        # turn actually carried a plan or calls.
        if tool_plan_chunks:
            message["tool_plan"] = "".join(tool_plan_chunks)
        if tool_calls:
            message["tool_calls"] = [tool_calls[index] for index in sorted(tool_calls)]

        return ChatStreamAggregated(
            id=message_id,
            finish_reason=finish_reason,
            message=message,
            usage=usage,
        )
    except Exception as exception:
        LOGGER.error(
            "Failed to aggregate cohere stream content, reason: %s",
            str(exception),
            exc_info=True,
        )
        return None


def _content_of(event: Any) -> Optional[Any]:
    delta = getattr(event, "delta", None)
    if delta is None:
        return None
    message = getattr(delta, "message", None)
    if message is None:
        return None
    return getattr(message, "content", None)


def _tool_plan_of(event: Any) -> Optional[str]:
    delta = getattr(event, "delta", None)
    if delta is None:
        return None
    message = getattr(delta, "message", None)
    if message is None:
        return None
    return getattr(message, "tool_plan", None)


def _tool_call_of(event: Any) -> Optional[Any]:
    delta = getattr(event, "delta", None)
    if delta is None:
        return None
    message = getattr(delta, "message", None)
    if message is None:
        return None
    return getattr(message, "tool_calls", None)


def _tool_call_entry(tool_call: Any) -> Dict[str, Any]:
    """Shape one streamed call like the non-streamed `ToolCallV2` dump."""
    function = getattr(tool_call, "function", None)
    return {
        "id": getattr(tool_call, "id", None),
        "type": getattr(tool_call, "type", None),
        "function": {
            "name": getattr(function, "name", None) if function is not None else None,
            "arguments": (
                getattr(function, "arguments", None) or ""
                if function is not None
                else ""
            ),
        },
    }


def _start_tool_call(tool_calls: Dict[int, Dict[str, Any]], event: Any) -> None:
    tool_call = _tool_call_of(event)
    if tool_call is None:
        return
    index = getattr(event, "index", None)
    tool_calls[index if index is not None else len(tool_calls)] = _tool_call_entry(
        tool_call
    )


def _append_tool_call_arguments(
    tool_calls: Dict[int, Dict[str, Any]], event: Any
) -> None:
    tool_call = _tool_call_of(event)
    if tool_call is None:
        return
    function = getattr(tool_call, "function", None)
    arguments = getattr(function, "arguments", None) if function is not None else None
    if not arguments:
        return
    index = getattr(event, "index", None)
    key = index if index is not None else 0
    entry = tool_calls.get(key)
    if entry is None:
        # A delta without a preceding start: keep the arguments instead of
        # silently dropping them, which is the failure this aggregator fixes.
        # The delta payload carries neither id nor type; Cohere v2 calls are
        # always function calls, so fill the type in.
        entry = _tool_call_entry(tool_call)
        entry["function"]["arguments"] = ""
        if entry["type"] is None:
            entry["type"] = "function"
        tool_calls[key] = entry
    entry["function"]["arguments"] += arguments


def _finish_tool_call(tool_calls: Dict[int, Dict[str, Any]], event: Any) -> None:
    tool_call = _tool_call_of(event)
    if tool_call is None:
        return
    index = getattr(event, "index", None)
    key = index if index is not None else 0
    entry = _tool_call_entry(tool_call)
    # The end event repeats the completed call; only adopt it when it carries
    # at least what the deltas accumulated, so a payload-less end event can
    # never erase already collected arguments.
    existing = tool_calls.get(key)
    if existing is not None and len(existing["function"]["arguments"]) > len(
        entry["function"]["arguments"]
    ):
        entry["function"]["arguments"] = existing["function"]["arguments"]
        if entry["id"] is None:
            entry["id"] = existing["id"]
        if entry["function"]["name"] is None:
            entry["function"]["name"] = existing["function"]["name"]
    tool_calls[key] = entry
