import logging
from typing import Any, Dict, List, Optional

import pydantic
from together.types.chat import ChatCompletionChunk

from opik.integrations import _streamed_tool_calls

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
    items: List[ChatCompletionChunk],
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
        reasoning_chunks: List[str] = []
        tool_calls = _streamed_tool_calls.StreamedToolCalls()

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

                # Together streams reasoning separately from content. It exposes
                # both `reasoning` and `reasoning_content`; models populate one or
                # the other, so take whichever is present.
                reasoning = delta.reasoning or delta.reasoning_content
                if reasoning:
                    reasoning_chunks.append(reasoning)

                for tool_call in getattr(delta, "tool_calls", None) or []:
                    tool_calls.add(tool_call)

            if chunk.choices and chunk.choices[0].finish_reason:
                aggregated_response["choices"][0]["finish_reason"] = chunk.choices[
                    0
                ].finish_reason

            if chunk.usage is not None:
                aggregated_response["usage"] = chunk.usage.model_dump()

        aggregated_response["choices"][0]["message"]["content"] = "".join(text_chunks)
        if reasoning_chunks:
            aggregated_response["choices"][0]["message"]["reasoning"] = "".join(
                reasoning_chunks
            )
        if streamed_calls := tool_calls.to_list():
            aggregated_response["choices"][0]["message"]["tool_calls"] = streamed_calls
        result = ChatCompletionChunksAggregated(**aggregated_response)

        return result
    except Exception as exception:
        LOGGER.error(
            "Failed to aggregate together stream content, reason: %s",
            str(exception),
            exc_info=True,
        )
        return None
