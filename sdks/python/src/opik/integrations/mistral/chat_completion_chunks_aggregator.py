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


def _tool_call_key(
    tool_call: Any,
    tool_calls_by_index: Dict[int, Dict[str, Any]],
    keys_by_call_id: Dict[str, int],
) -> int:
    """Which reassembled call a streamed fragment belongs to.

    ``index`` identifies a call only when the stream sent one: the model type
    declares ``index: Optional[int] = 0`` (``mistralai/models/toolcall.py:29``),
    so a payload that omits it parses as ``0`` and every call in the stream would
    be merged into that one slot. Without a sent index, a fragment repeating a
    known ``id`` continues that call and any other fragment opens a new one --
    ``function.name`` and ``function.arguments`` are required by the model, so
    every fragment the SDK accepts is a complete call.
    """
    sent_index = tool_call.index
    if "index" in tool_call.model_fields_set and isinstance(sent_index, int):
        return sent_index

    if "id" in tool_call.model_fields_set and tool_call.id:
        known_key = keys_by_call_id.get(tool_call.id)
        if known_key is not None:
            return known_key

    return max(tool_calls_by_index, default=-1) + 1


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
        keys_by_call_id: Dict[str, int] = {}

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
                    # Mistral emits each tool call complete in a single chunk, so
                    # ``index`` is the identity to accumulate by -- when the stream
                    # sends one. See ``_tool_call_key`` for what happens when it
                    # does not.
                    for tool_call in delta.tool_calls:
                        index = _tool_call_key(
                            tool_call, tool_calls_by_index, keys_by_call_id
                        )
                        payload = tool_call.model_dump(mode="json")
                        _merge_tool_call(tool_calls_by_index, index, payload)
                        if "id" in tool_call.model_fields_set and tool_call.id:
                            keys_by_call_id[tool_call.id] = index

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
