import logging
from typing import Any, Dict, List, Optional

import pydantic
from openai.types.chat import chat_completion_chunk

from opik import logging_messages, types

LOGGER = logging.getLogger(__name__)


class ChatCompletionChunksAggregated(pydantic.BaseModel):
    choices: List[Dict[str, Any]]
    created: int
    id: str
    model: str
    object: str
    system_fingerprint: str
    usage: types.UsageDict


def aggregate(
    items: List[chat_completion_chunk.ChatCompletionChunk],
) -> Optional[ChatCompletionChunksAggregated]:
    # TODO: check if there are scenarios when stream contains more than one choice
    try:
        first_chunk = items[0]

        aggregated_response = {
            "choices": [{"index": 0, "message": {"role": "", "content": ""}}],
            "id": first_chunk.id,
            "created": first_chunk.created,
            "model": first_chunk.model,
            "object": "chat.completion",
            "system_fingerprint": first_chunk.system_fingerprint,
        }

        text_chunks: List[str] = []

        for i, chunk in enumerate(items):
            if chunk.choices and chunk.choices[0].delta:
                delta = chunk.choices[0].delta

                if delta.role is None:
                    print()
                elif len(delta.role) <= 3:
                    print()

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

            if chunk.usage:
                aggregated_response["usage"] = chunk.usage.model_dump()

        aggregated_response["choices"][0]["message"]["content"] = "".join(text_chunks)
        result = ChatCompletionChunksAggregated(**aggregated_response)

        return result
    except Exception as exception:
        LOGGER.error(
            logging_messages.FAILED_TO_PARSE_OPENAI_STREAM_CONTENT,
            str(exception),
            exc_info=True,
        )
        return None
