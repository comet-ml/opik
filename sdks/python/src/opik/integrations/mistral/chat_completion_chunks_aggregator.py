import logging
from typing import Any, Dict, List, Optional

import pydantic

LOGGER = logging.getLogger(__name__)


class MistralChatCompletionChunksAggregated(pydantic.BaseModel):
    id: str
    model: str
    object: Optional[str] = None
    created: Optional[int] = None
    choices: List[Dict[str, Any]]
    usage: Optional[Dict[str, Any]] = None


def aggregate(
    items: List[Any],
) -> Optional[MistralChatCompletionChunksAggregated]:
    """Merge a list of ``CompletionEvent`` stream chunks into a single response.

    Each item is a ``mistralai.CompletionEvent`` whose ``.data`` is a
    ``CompletionChunk``. The shape mirrors ``ChatCompletionResponse`` so the
    decorator's end preprocessor handles streamed and non-streamed calls the
    same way.
    """
    try:
        chunks = [item.data for item in items]
        first_chunk = chunks[0]

        aggregated_response: Dict[str, Any] = {
            "id": first_chunk.id,
            "model": first_chunk.model,
            "object": first_chunk.object,
            "created": first_chunk.created,
            "choices": [{"index": 0, "message": {"role": "", "content": ""}}],
            "usage": None,
        }

        text_chunks: List[str] = []

        for chunk in chunks:
            if chunk.choices and chunk.choices[0].delta:
                delta = chunk.choices[0].delta

                if (
                    delta.role
                    and not aggregated_response["choices"][0]["message"]["role"]
                ):
                    aggregated_response["choices"][0]["message"]["role"] = delta.role

                if delta.content:
                    text_chunks.append(delta.content)

                if delta.tool_calls:
                    aggregated_response["choices"][0]["message"]["tool_calls"] = [
                        tool_call.model_dump(mode="json")
                        for tool_call in delta.tool_calls
                    ]

            if chunk.choices and chunk.choices[0].finish_reason:
                aggregated_response["choices"][0]["finish_reason"] = chunk.choices[
                    0
                ].finish_reason

            if chunk.usage:
                aggregated_response["usage"] = chunk.usage.model_dump(mode="json")

        aggregated_response["choices"][0]["message"]["content"] = "".join(text_chunks)

        return MistralChatCompletionChunksAggregated(**aggregated_response)
    except Exception:
        LOGGER.error("Failed to aggregate Mistral stream chunks", exc_info=True)
        return None
