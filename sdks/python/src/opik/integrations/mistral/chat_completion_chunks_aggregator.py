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

    A fragment that repeats a known ``id`` continues that call, even when it also
    sends an ``index``: the id is already mapped to a slot, and taking the index
    at face value moves the rest of the call into a second one.

    Otherwise ``index`` identifies a call only when the stream sent one: the model
    type declares ``index: Optional[int] = 0`` (``mistralai/models/toolcall.py``),
    so a payload that omits it parses as ``0`` and every call in the stream would
    be merged into that one slot. Any remaining fragment opens a new call --
    ``function.name`` and ``function.arguments`` are required by the model, so
    every fragment the SDK accepts is a complete call.

    A call opened here is keyed below every key already in use, so it is negative
    while provider indexes are not: a stream that sends ``index`` for some
    fragments and omits it for others cannot land a provider index on a slot this
    function invented, which would merge two different calls into one.
    """
    if "id" in tool_call.model_fields_set and tool_call.id:
        known_key = keys_by_call_id.get(tool_call.id)
        if known_key is not None:
            return known_key

    sent_index = tool_call.index
    if "index" in tool_call.model_fields_set and isinstance(sent_index, int):
        return sent_index

    return min((key for key in tool_calls_by_index if key < 0), default=0) - 1


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
        # Reasoning models stream lists of content chunks (thinking, then text)
        # instead of strings; those are kept as plain data, in order.
        content_chunks: List[Dict[str, Any]] = []
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

                if isinstance(delta.content, str):
                    if delta.content:
                        text_chunks.append(delta.content)
                elif delta.content:
                    # Text that arrived before this list keeps its place in order.
                    if text_chunks:
                        content_chunks.append(
                            {"type": "text", "text": "".join(text_chunks)}
                        )
                        text_chunks = []
                    for content_chunk in delta.content:
                        content_chunks.append(
                            content_chunk.model_dump(mode="json", exclude_none=True)
                            if hasattr(content_chunk, "model_dump")
                            else content_chunk
                        )

                if delta.tool_calls:
                    # ``_tool_call_key`` owns how a fragment is matched to a call.
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

        if content_chunks:
            if text_chunks:
                content_chunks.append({"type": "text", "text": "".join(text_chunks)})
            aggregated_response["choices"][0]["message"]["content"] = content_chunks
        else:
            aggregated_response["choices"][0]["message"]["content"] = "".join(
                text_chunks
            )
        if tool_calls_by_index:
            # Calls with a stream-provided index retain index order; calls opened
            # without one follow the order their fragments arrived.
            ordered_keys = sorted(key for key in tool_calls_by_index if key >= 0) + [
                key for key in tool_calls_by_index if key < 0
            ]
            aggregated_response["choices"][0]["message"]["tool_calls"] = [
                tool_calls_by_index[key] for key in ordered_keys
            ]

        return MistralChatCompletionChunksAggregated(**aggregated_response)
    except Exception:
        LOGGER.error("Failed to aggregate Mistral stream chunks", exc_info=True)
        return None
