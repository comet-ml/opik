import logging
from typing import Any, Dict, List, Optional

import pydantic
from openai.types.chat import chat_completion_chunk

import opik.logging_messages as logging_messages

LOGGER = logging.getLogger(__name__)


class ChatCompletionChunksAggregated(pydantic.BaseModel):
    choices: List[Dict[str, Any]]
    created: int
    id: str
    model: str
    object: str
    system_fingerprint: Optional[str]
    usage: Optional[Dict[str, Any]]


def _resolve_tool_call_key(
    tool_call: chat_completion_chunk.ChoiceDeltaToolCall,
    position: int,
    fragment_count: int,
    tool_calls_by_index: Dict[int, Dict[str, Any]],
    keys_by_call_id: Dict[str, int],
) -> int:
    """Which reassembled tool call this fragment belongs to.

    ``index`` is the identity the wire format provides. Fragments of a stream
    that omits it are attributed by their ``id`` when they carry one, by their
    position only while a single call is in flight or the chunk repeats every
    slot, and otherwise to the most recently opened call -- the only one a
    provider can still be streaming arguments for.
    """
    if tool_call.index is not None:
        return tool_call.index

    if tool_call.id is not None:
        known_key = keys_by_call_id.get(tool_call.id)
        if known_key is not None:
            return known_key
        return max(tool_calls_by_index, default=-1) + 1

    if len(tool_calls_by_index) <= 1 or fragment_count == len(tool_calls_by_index):
        return position

    return max(tool_calls_by_index)


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
    items: List[chat_completion_chunk.ChatCompletionChunk],
) -> Optional[ChatCompletionChunksAggregated]:
    # TODO: check if there are scenarios when stream contains more than one choice
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
        tool_calls_by_index: Dict[int, Dict[str, Any]] = {}
        keys_by_call_id: Dict[str, int] = {}

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

                if delta.tool_calls:
                    fragment_count = len(delta.tool_calls)
                    for position, tool_call in enumerate(delta.tool_calls):
                        index = _resolve_tool_call_key(
                            tool_call,
                            position,
                            fragment_count,
                            tool_calls_by_index,
                            keys_by_call_id,
                        )
                        tool_call_payload = tool_call.model_dump(exclude_none=True)
                        # `index` orders fragments inside the stream; a
                        # non-streamed response message does not carry it, and
                        # dropping it here keeps both shapes interchangeable.
                        tool_call_payload.pop("index", None)
                        _merge_tool_call(tool_calls_by_index, index, tool_call_payload)
                        call_id = tool_call_payload.get("id")
                        if call_id is not None:
                            keys_by_call_id[call_id] = index

            if chunk.choices and chunk.choices[0].finish_reason:
                aggregated_response["choices"][0]["finish_reason"] = chunk.choices[
                    0
                ].finish_reason

            if chunk.usage:
                aggregated_response["usage"] = chunk.usage.model_dump()

        aggregated_response["choices"][0]["message"]["content"] = "".join(text_chunks)
        if tool_calls_by_index:
            aggregated_response["choices"][0]["message"]["tool_calls"] = [
                tool_calls_by_index[index] for index in sorted(tool_calls_by_index)
            ]

        result = ChatCompletionChunksAggregated(**aggregated_response)

        return result
    except Exception as exception:
        LOGGER.error(
            logging_messages.FAILED_TO_PARSE_OPENAI_STREAM_CONTENT,
            str(exception),
            exc_info=True,
        )
        return None
