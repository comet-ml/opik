import logging
from typing import Any, Dict, List, Optional

import pydantic
from groq.types.chat import chat_completion_chunk

LOGGER = logging.getLogger(__name__)


class ChatCompletionChunksAggregated(pydantic.BaseModel):
    choices: List[Dict[str, Any]]
    created: int
    id: str
    model: str
    object: str
    system_fingerprint: Optional[str]
    usage: Optional[Dict[str, Any]]


def aggregate(
    items: List[chat_completion_chunk.ChatCompletionChunk],
) -> Optional[ChatCompletionChunksAggregated]:
    try:
        first_chunk = items[0]

        aggregated_response = {
            "choices": [{"index": 0, "message": {"role": "", "content": ""}}],
            "created": first_chunk.created,
            "id": first_chunk.id,
            "model": first_chunk.model,
            "object": "chat.completion",
            "system_fingerprint": first_chunk.system_fingerprint,
            "usage": None,
        }

        text_chunks: List[str] = []

        for chunk in items:
            if chunk.choices and chunk.choices[0].delta:
                delta = chunk.choices[0].delta

                if (
                    delta.role
                    and not aggregated_response["choices"][0]["message"]["role"]
                ):
                    aggregated_response["choices"][0]["message"]["role"] = delta.role

                if delta.content:
                    text_chunks.append(delta.content)

            if chunk.choices and chunk.choices[0].finish_reason:
                aggregated_response["choices"][0]["finish_reason"] = chunk.choices[
                    0
                ].finish_reason

            # Groq places usage stats on the terminal chunk under x_groq.usage.
            x_groq = getattr(chunk, "x_groq", None)
            if x_groq is not None and getattr(x_groq, "usage", None) is not None:
                aggregated_response["usage"] = x_groq.usage.model_dump()
            elif getattr(chunk, "usage", None) is not None:
                aggregated_response["usage"] = chunk.usage.model_dump()

        aggregated_response["choices"][0]["message"]["content"] = "".join(text_chunks)
        result = ChatCompletionChunksAggregated(**aggregated_response)

        return result
    except Exception as exception:
        LOGGER.error(
            "Failed to aggregate groq stream content, reason: %s",
            str(exception),
            exc_info=True,
        )
        return None
