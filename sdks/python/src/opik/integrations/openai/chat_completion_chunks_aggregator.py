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
    try:
        first_chunk = items[0]

        aggregated_response: Dict[str, Any] = {
            "choices": [],
            "created": first_chunk.created,
            "id": first_chunk.id,
            "model": first_chunk.model,
            "object": "chat.completion",
            "system_fingerprint": first_chunk.system_fingerprint,
            "usage": None,
        }

        # With `n` > 1 every chunk carries the delta of one choice, named by
        # `choice.index`, so each choice is reassembled on its own.
        choices_by_index: Dict[int, Dict[str, Any]] = {}
        text_chunks_by_index: Dict[int, List[str]] = {}
        tool_calls_by_choice: Dict[int, Dict[int, Dict[str, Any]]] = {}

        for chunk in items:
            for choice in chunk.choices or []:
                choice_index = choice.index or 0
                aggregated_choice = choices_by_index.setdefault(
                    choice_index,
                    {"index": choice_index, "message": {"role": "", "content": ""}},
                )
                message = aggregated_choice["message"]
                delta = choice.delta

                if delta:
                    if delta.role and not message["role"]:
                        message["role"] = delta.role

                    if delta.content:
                        text_chunks_by_index.setdefault(choice_index, []).append(
                            delta.content
                        )

                    if delta.tool_calls:
                        tool_calls_by_index = tool_calls_by_choice.setdefault(
                            choice_index, {}
                        )
                        for tool_call in delta.tool_calls:
                            tool_call_payload = tool_call.model_dump(exclude_none=True)
                            # `index` orders fragments inside the stream; a
                            # non-streamed response message does not carry it, and
                            # dropping it here keeps both shapes interchangeable.
                            tool_call_payload.pop("index", None)
                            _merge_tool_call(
                                tool_calls_by_index,
                                tool_call.index,
                                tool_call_payload,
                            )

                if choice.finish_reason:
                    aggregated_choice["finish_reason"] = choice.finish_reason

            if chunk.usage:
                aggregated_response["usage"] = chunk.usage.model_dump()

        if not choices_by_index:
            choices_by_index[0] = {
                "index": 0,
                "message": {"role": "", "content": ""},
            }

        for choice_index in sorted(choices_by_index):
            aggregated_choice = choices_by_index[choice_index]
            message = aggregated_choice["message"]
            message["content"] = "".join(text_chunks_by_index.get(choice_index, []))
            choice_tool_calls = tool_calls_by_choice.get(choice_index)
            if choice_tool_calls:
                message["tool_calls"] = [
                    choice_tool_calls[index] for index in sorted(choice_tool_calls)
                ]
            aggregated_response["choices"].append(aggregated_choice)

        result = ChatCompletionChunksAggregated(**aggregated_response)

        return result
    except Exception as exception:
        LOGGER.error(
            logging_messages.FAILED_TO_PARSE_OPENAI_STREAM_CONTENT,
            str(exception),
            exc_info=True,
        )
        return None
