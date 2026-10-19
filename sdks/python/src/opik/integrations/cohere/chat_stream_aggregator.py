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
    """
    try:
        message_id: Optional[str] = None
        finish_reason: Optional[str] = None
        usage: Optional[Dict[str, Any]] = None
        text_chunks: List[str] = []
        thinking_chunks: List[str] = []

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
