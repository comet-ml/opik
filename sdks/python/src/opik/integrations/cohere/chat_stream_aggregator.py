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
    A tool call is its own set of events -- `tool-plan-delta`, `tool-call-start`
    and `tool-call-delta` -- and is folded onto the message here, which is where
    `chat()` reports it.
    """
    try:
        message_id: Optional[str] = None
        finish_reason: Optional[str] = None
        usage: Optional[Dict[str, Any]] = None
        text_chunks: List[str] = []
        thinking_chunks: List[str] = []
        tool_plan_chunks: List[str] = []
        tool_calls: List[Dict[str, Any]] = []

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
                # an empty fragment is still a caller supplied value, only an
                # absent one is skipped
                if tool_plan is not None:
                    tool_plan_chunks.append(tool_plan)

            elif event_type == "tool-call-start":
                _start_tool_call(tool_calls, event)

            elif event_type == "tool-call-delta":
                _extend_tool_call_arguments(tool_calls, event)

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
        if tool_plan_chunks:
            message["tool_plan"] = "".join(tool_plan_chunks)
        if tool_calls:
            message["tool_calls"] = tool_calls

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


def _tool_call_fragments_of(event: Any) -> List[Any]:
    """The tool-call fragment an event carries, always as a list.

    Cohere sends one call per event; a list is accepted too, so a stream that
    batches them into a single event still reaches the span.
    """
    delta = getattr(event, "delta", None)
    if delta is None:
        return []
    message = getattr(delta, "message", None)
    if message is None:
        return []
    tool_calls = getattr(message, "tool_calls", None)
    if tool_calls is None:
        return []
    if isinstance(tool_calls, list):
        return tool_calls
    return [tool_calls]


def _tool_call_slot(
    tool_calls: List[Dict[str, Any]], index: Optional[int]
) -> Dict[str, Any]:
    """The call `index` identifies, created on first use.

    Every tool-call event carries the index, which is what keeps parallel calls
    apart. A fragment without one can only belong to the last call.
    """
    if index is None:
        if not tool_calls:
            tool_calls.append({})
        return tool_calls[-1]

    while len(tool_calls) <= index:
        tool_calls.append({})

    return tool_calls[index]


def _start_tool_call(tool_calls: List[Dict[str, Any]], event: Any) -> None:
    """Record the call a `tool-call-start` event opens, as `chat()` reports it."""
    for fragment in _tool_call_fragments_of(event):
        if not hasattr(fragment, "model_dump"):
            continue
        slot = _tool_call_slot(tool_calls, getattr(event, "index", None))
        slot.update(fragment.model_dump(mode="json"))


def _extend_tool_call_arguments(tool_calls: List[Dict[str, Any]], event: Any) -> None:
    """Append the arguments fragment a `tool-call-delta` event carries."""
    for fragment in _tool_call_fragments_of(event):
        function = getattr(fragment, "function", None)
        arguments = getattr(function, "arguments", None)
        if not arguments:
            continue

        slot = _tool_call_slot(tool_calls, getattr(event, "index", None))
        slot_function = slot.setdefault("function", {})
        slot_function["arguments"] = slot_function.get("arguments", "") + arguments
