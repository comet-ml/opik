import json
from typing import Any, Dict, List, Optional, Tuple

from openai.types.chat import (
    ChatCompletionChunk,
    ChatCompletionMessage,
    chat_completion_chunk,
)

from opik.integrations.openai import chat_completion_chunks_aggregator

CREATED = 1750000000
CHUNK_ID = "chatcmpl-1"
MODEL = "gpt-4o-mini"

WEATHER_ARGS_PART_1 = '{"location"'
WEATHER_ARGS_PART_2 = ': "Paris"}'
WEATHER_ARGS = WEATHER_ARGS_PART_1 + WEATHER_ARGS_PART_2

FIRST_ARGS = '{"a": 1}'
SECOND_ARGS_PART_1 = '{"b": '
SECOND_ARGS_PART_2 = "2}"
SECOND_ARGS = SECOND_ARGS_PART_1 + SECOND_ARGS_PART_2

NO_INDEX_ARGS = '{"x": 1}'
CITY_ARGS = '{"city": "Paris"}'


def _delta(**fields: Any) -> chat_completion_chunk.ChoiceDelta:
    """Build a delta carrying only the keys a provider actually sends.

    ``model_construct`` leaves absent keys absent, the way the openai client
    parses streamed events: continuation tool-call fragments repeat neither
    ``id``/``type`` nor the function ``name``.
    """
    return chat_completion_chunk.ChoiceDelta.model_construct(**fields)


def _tool_call(
    index: Optional[int],
    *,
    call_id: Optional[str] = None,
    call_type: Optional[str] = None,
    name: Optional[str] = None,
    arguments: Optional[str] = None,
) -> chat_completion_chunk.ChoiceDeltaToolCall:
    function: Dict[str, Any] = {}
    if name is not None:
        function["name"] = name
    if arguments is not None:
        function["arguments"] = arguments

    fields: Dict[str, Any] = {"function": function}
    if index is not None:
        fields["index"] = index
    if call_id is not None:
        fields["id"] = call_id
    if call_type is not None:
        fields["type"] = call_type

    return chat_completion_chunk.ChoiceDeltaToolCall.model_construct(**fields)


def _chunk(
    choices: List[Tuple[int, Optional[str], chat_completion_chunk.ChoiceDelta]],
    usage: Optional[Dict[str, Any]] = None,
) -> ChatCompletionChunk:
    return ChatCompletionChunk.model_construct(
        id=CHUNK_ID,
        model=MODEL,
        object="chat.completion.chunk",
        created=CREATED,
        choices=[
            chat_completion_chunk.Choice.model_construct(
                index=index,
                finish_reason=finish_reason,
                logprobs=None,
                delta=delta,
            )
            for index, finish_reason, delta in choices
        ],
        system_fingerprint=None,
        usage=usage,
    )


def _tool_call_stream() -> List[ChatCompletionChunk]:
    """A streamed response that only invokes one tool, arguments split over chunks."""
    return [
        _chunk([(0, None, _delta(role="assistant"))]),
        _chunk(
            [
                (
                    0,
                    None,
                    _delta(
                        tool_calls=[
                            _tool_call(
                                0,
                                call_id="call_9F2a",
                                call_type="function",
                                name="get_weather",
                                arguments="",
                            )
                        ]
                    ),
                )
            ]
        ),
        _chunk(
            [
                (
                    0,
                    None,
                    _delta(tool_calls=[_tool_call(0, arguments=WEATHER_ARGS_PART_1)]),
                )
            ]
        ),
        _chunk(
            [
                (
                    0,
                    None,
                    _delta(tool_calls=[_tool_call(0, arguments=WEATHER_ARGS_PART_2)]),
                )
            ]
        ),
        _chunk([(0, "tool_calls", _delta())]),
        _chunk(
            [],
            usage={"prompt_tokens": 41, "completion_tokens": 17, "total_tokens": 58},
        ),
    ]


def _message(aggregated: Any) -> Dict[str, Any]:
    return aggregated.model_dump()["choices"][0]["message"]


def test_aggregate__stream_with_one_tool_call__keeps_the_reassembled_call() -> None:
    aggregated = chat_completion_chunks_aggregator.aggregate(_tool_call_stream())

    assert aggregated is not None
    assert _message(aggregated) == {
        "role": "assistant",
        "content": "",
        "tool_calls": [
            {
                "id": "call_9F2a",
                "type": "function",
                "function": {"name": "get_weather", "arguments": WEATHER_ARGS},
            }
        ],
    }
    assert aggregated.model_dump()["choices"][0]["finish_reason"] == "tool_calls"


def test_aggregate__stream_tool_call__reports_the_non_streamed_message_shape() -> None:
    """The recorded output must be interchangeable with a non-streamed response."""
    non_streamed_message = ChatCompletionMessage.model_validate(
        {
            "role": "assistant",
            "content": None,
            "tool_calls": [
                {
                    "id": "call_9F2a",
                    "type": "function",
                    "function": {"name": "get_weather", "arguments": WEATHER_ARGS},
                }
            ],
        }
    )

    aggregated = chat_completion_chunks_aggregator.aggregate(_tool_call_stream())

    assert aggregated is not None
    assert (
        _message(aggregated)["tool_calls"]
        == json.loads(non_streamed_message.model_dump_json())["tool_calls"]
    )


def test_aggregate__stream_with_parallel_tool_calls__keeps_every_call() -> None:
    stream = [
        _chunk([(0, None, _delta(role="assistant"))]),
        _chunk(
            [
                (
                    0,
                    None,
                    _delta(
                        tool_calls=[
                            _tool_call(
                                0,
                                call_id="call_first",
                                call_type="function",
                                name="fn_first",
                                arguments="",
                            ),
                            _tool_call(
                                1,
                                call_id="call_second",
                                call_type="function",
                                name="fn_second",
                                arguments="",
                            ),
                        ]
                    ),
                )
            ]
        ),
        _chunk(
            [
                (
                    0,
                    None,
                    _delta(
                        tool_calls=[
                            _tool_call(0, arguments=FIRST_ARGS),
                            _tool_call(1, arguments=SECOND_ARGS_PART_1),
                        ]
                    ),
                )
            ]
        ),
        _chunk(
            [
                (
                    0,
                    None,
                    _delta(tool_calls=[_tool_call(1, arguments=SECOND_ARGS_PART_2)]),
                )
            ]
        ),
        _chunk([(0, "tool_calls", _delta())]),
    ]

    aggregated = chat_completion_chunks_aggregator.aggregate(stream)

    assert aggregated is not None
    assert _message(aggregated)["tool_calls"] == [
        {
            "id": "call_first",
            "type": "function",
            "function": {"name": "fn_first", "arguments": FIRST_ARGS},
        },
        {
            "id": "call_second",
            "type": "function",
            "function": {"name": "fn_second", "arguments": SECOND_ARGS},
        },
    ]


def test_aggregate__stream_with_content_and_a_tool_call__keeps_both() -> None:
    stream = [
        _chunk([(0, None, _delta(role="assistant", content="Let me check"))]),
        _chunk([(0, None, _delta(content=" the weather."))]),
        _chunk(
            [
                (
                    0,
                    None,
                    _delta(
                        tool_calls=[
                            _tool_call(
                                0,
                                call_id="call_B1",
                                call_type="function",
                                name="get_weather",
                                arguments=CITY_ARGS,
                            )
                        ]
                    ),
                )
            ]
        ),
        _chunk([(0, "tool_calls", _delta())]),
    ]

    aggregated = chat_completion_chunks_aggregator.aggregate(stream)

    assert aggregated is not None
    message = _message(aggregated)
    assert message["content"] == "Let me check the weather."
    assert message["tool_calls"] == [
        {
            "id": "call_B1",
            "type": "function",
            "function": {"name": "get_weather", "arguments": CITY_ARGS},
        }
    ]


def test_aggregate__tool_call_fragments_without_index__fall_back_to_position() -> None:
    """`ChoiceDeltaToolCall.index` is optional, so a provider may omit it."""
    stream = [
        _chunk(
            [
                (
                    0,
                    None,
                    _delta(
                        tool_calls=[
                            _tool_call(
                                None,
                                call_id="call_no_index",
                                call_type="function",
                                name="fn_no_index",
                                arguments="",
                            )
                        ]
                    ),
                )
            ]
        ),
        _chunk(
            [
                (
                    0,
                    None,
                    _delta(tool_calls=[_tool_call(None, arguments=NO_INDEX_ARGS)]),
                )
            ]
        ),
        _chunk([(0, "tool_calls", _delta())]),
    ]

    aggregated = chat_completion_chunks_aggregator.aggregate(stream)

    assert aggregated is not None
    assert _message(aggregated)["tool_calls"] == [
        {
            "id": "call_no_index",
            "type": "function",
            "function": {"name": "fn_no_index", "arguments": NO_INDEX_ARGS},
        }
    ]


def test_aggregate__text_only_stream__output_unchanged() -> None:
    stream = [
        _chunk([(0, None, _delta(role="assistant", content="Hello"))]),
        _chunk([(0, None, _delta(content=" world"))]),
        _chunk([(0, "stop", _delta())]),
        _chunk(
            [],
            usage={"prompt_tokens": 5, "completion_tokens": 2, "total_tokens": 7},
        ),
    ]

    aggregated = chat_completion_chunks_aggregator.aggregate(stream)

    assert aggregated is not None
    assert aggregated.model_dump() == {
        "choices": [
            {
                "index": 0,
                "message": {"role": "assistant", "content": "Hello world"},
                "finish_reason": "stop",
            }
        ],
        "created": CREATED,
        "id": CHUNK_ID,
        "model": MODEL,
        "object": "chat.completion",
        "system_fingerprint": None,
        "usage": {
            "prompt_tokens": 5,
            "completion_tokens": 2,
            "total_tokens": 7,
            "completion_tokens_details": None,
            "prompt_tokens_details": None,
        },
    }


def test_aggregate__usage_only_final_chunk__keeps_usage_and_adds_no_tool_calls_key() -> (
    None
):
    stream = [
        _chunk([(0, None, _delta(role="assistant", content="Hi"))]),
        _chunk([(0, "stop", _delta())]),
        _chunk(
            [],
            usage={"prompt_tokens": 3, "completion_tokens": 1, "total_tokens": 4},
        ),
    ]

    aggregated = chat_completion_chunks_aggregator.aggregate(stream)

    assert aggregated is not None
    assert "tool_calls" not in _message(aggregated)
    assert aggregated.model_dump()["usage"]["total_tokens"] == 4
