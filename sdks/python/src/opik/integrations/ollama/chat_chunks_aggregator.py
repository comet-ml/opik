import logging
from typing import Any, Dict, List, Optional

from ollama._types import ChatResponse

LOGGER = logging.getLogger(__name__)


def aggregate(items: List[ChatResponse]) -> Optional[ChatResponse]:
    """Fold a streamed sequence of ChatResponse chunks into a single response.

    Ollama streams one ChatResponse per token-ish chunk, each carrying a partial
    ``message.content``. Only the final chunk (``done=True``) carries the timing
    and token counts, so the aggregate takes its scalar fields from there and
    concatenates the text from all of them.
    """
    try:
        if not items:
            return None

        last = items[-1]
        content: List[str] = []
        thinking: List[str] = []
        tool_calls: List[Any] = []
        role: Optional[str] = None

        for chunk in items:
            message = chunk.message
            if message is None:
                continue
            if role is None and message.role:
                role = message.role
            if message.content:
                content.append(message.content)
            # Ollama streams a reasoning model's chain of thought in its own
            # field, exactly as it streams content. `thinking` was added in
            # ollama 0.5.0; on 0.4.x the attribute is absent, so read it
            # defensively rather than raising into the broad handler below --
            # that would discard the whole aggregate and log an empty output.
            chunk_thinking = getattr(message, "thinking", None)
            if chunk_thinking:
                thinking.append(chunk_thinking)
            if message.tool_calls:
                tool_calls.extend(message.tool_calls)

        aggregated: Dict[str, Any] = last.model_dump()
        message_dict: Dict[str, Any] = aggregated.get("message") or {}
        message_dict["role"] = role or message_dict.get("role") or "assistant"
        message_dict["content"] = "".join(content)
        if thinking:
            message_dict["thinking"] = "".join(thinking)
        if tool_calls:
            message_dict["tool_calls"] = [
                call if isinstance(call, dict) else call.model_dump()
                for call in tool_calls
            ]
        aggregated["message"] = message_dict

        return ChatResponse(**aggregated)
    except Exception as exception:
        LOGGER.error(
            "Failed to aggregate ollama stream content, reason: %s",
            str(exception),
            exc_info=True,
        )
        return None
