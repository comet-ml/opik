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


def _merge_tool_call(
    tool_calls_by_index: Dict[int, Dict[str, Any]],
    index: int,
    delta: Dict[str, Any],
) -> None:
    existing = tool_calls_by_index.get(index)
    if existing is None:
        tool_calls_by_index[index] = delta
        return

    for key in ("id", "type"):
        if not existing.get(key) and delta.get(key):
            existing[key] = delta[key]

    delta_function = delta.get("function") or {}
    existing_function = existing.setdefault("function", {})
    if not existing_function.get("name") and delta_function.get("name"):
        existing_function["name"] = delta_function["name"]
    if delta_function.get("arguments"):
        existing_function["arguments"] = (
            existing_function.get("arguments") or ""
        ) + delta_function["arguments"]


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
        tool_calls_by_index: Dict[int, Dict[str, Any]] = {}

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
                    # Mistral currently emits each tool call complete in a single
                    # chunk, but accumulate by index (concatenating streamed
                    # argument fragments) so nothing is lost if a call is ever
                    # split across chunks.
                    for position, tool_call in enumerate(delta.tool_calls):
                        index = (
                            tool_call.index if tool_call.index is not None else position
                        )
                        _merge_tool_call(
                            tool_calls_by_index,
                            index,
                            tool_call.model_dump(mode="json"),
                        )

            if chunk.choices and chunk.choices[0].finish_reason:
                aggregated_response["choices"][0]["finish_reason"] = chunk.choices[
                    0
                ].finish_reason

            if chunk.usage:
                aggregated_response["usage"] = chunk.usage.model_dump(mode="json")

        aggregated_response["choices"][0]["message"]["content"] = "".join(text_chunks)
        if tool_calls_by_index:
            aggregated_response["choices"][0]["message"]["tool_calls"] = [
                tool_calls_by_index[index] for index in sorted(tool_calls_by_index)
            ]

        return MistralChatCompletionChunksAggregated(**aggregated_response)
    except Exception:
        LOGGER.error("Failed to aggregate Mistral stream chunks", exc_info=True)
        return None
