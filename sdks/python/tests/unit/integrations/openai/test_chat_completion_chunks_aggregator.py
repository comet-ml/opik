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


def test_aggregate__single_anonymous_fragment__does_not_rewrite_another_call() -> None:
    """One fragment with no index cannot be aligned by its position in the chunk."""
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
                                call_id="call_first",
                                call_type="function",
                                name="get_weather",
                                arguments='{"x":',
                            ),
                            _tool_call(
                                None,
                                call_id="call_second",
                                call_type="function",
                                name="get_time",
                                arguments='{"y":',
                            ),
                        ]
                    ),
                )
            ]
        ),
        _chunk([(0, None, _delta(tool_calls=[_tool_call(None, arguments="2}")]))]),
        _chunk([(0, "tool_calls", _delta())]),
    ]

    aggregated = chat_completion_chunks_aggregator.aggregate(stream)

    assert aggregated is not None
    assert _message(aggregated)["tool_calls"] == [
        {
            "id": "call_first",
            "type": "function",
            "function": {"name": "get_weather", "arguments": '{"x":'},
        },
        {
            "id": "call_second",
            "type": "function",
            "function": {"name": "get_time", "arguments": '{"y":2}'},
        },
    ]


def test_aggregate__unindexed_calls_opened_in_later_chunks__stay_separate() -> None:
    """A second `id` opens a second call even when the stream carries no index."""
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
                                call_id="call_one",
                                call_type="function",
                                name="first_fn",
                                arguments='{"a": 1}',
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
                    _delta(
                        tool_calls=[
                            _tool_call(
                                None,
                                call_id="call_two",
                                call_type="function",
                                name="second_fn",
                                arguments='{"b":',
                            )
                        ]
                    ),
                )
            ]
        ),
        _chunk([(0, None, _delta(tool_calls=[_tool_call(None, arguments="2}")]))]),
        _chunk([(0, "tool_calls", _delta())]),
    ]

    aggregated = chat_completion_chunks_aggregator.aggregate(stream)

    assert aggregated is not None
    assert _message(aggregated)["tool_calls"] == [
        {
            "id": "call_one",
            "type": "function",
            "function": {"name": "first_fn", "arguments": '{"a": 1}'},
        },
        {
            "id": "call_two",
            "type": "function",
            "function": {"name": "second_fn", "arguments": '{"b":2}'},
        },
    ]


def test_aggregate__fragment_with_explicit_nulls__reports_no_null_values() -> None:
    """Some gateways send `"id": null` instead of leaving the key out."""
    null_fragment = chat_completion_chunk.ChoiceDeltaToolCall.model_construct(
        index=0,
        id=None,
        type=None,
        function=chat_completion_chunk.ChoiceDeltaToolCallFunction(
            name=None, arguments=CITY_ARGS
        ),
    )
    stream = [
        _chunk([(0, None, _delta(tool_calls=[null_fragment]))]),
        _chunk([(0, "stop", _delta())]),
    ]

    aggregated = chat_completion_chunks_aggregator.aggregate(stream)

    assert aggregated is not None
    assert _message(aggregated)["tool_calls"] == [
        {"function": {"arguments": CITY_ARGS}}
    ]


def test_aggregate__chunks_parsed_from_sse_payloads__reassembles_the_call() -> None:
    """`model_validate_json` is how the client turns `data:` lines into chunks."""

    def payload(
        delta: Dict[str, Any],
        finish_reason: Optional[str] = None,
        usage: Optional[Dict[str, Any]] = None,
    ) -> str:
        body: Dict[str, Any] = {
            "id": CHUNK_ID,
            "object": "chat.completion.chunk",
            "created": CREATED,
            "model": MODEL,
            "choices": [
                {
                    "index": 0,
                    "delta": delta,
                    "logprobs": None,
                    "finish_reason": finish_reason,
                }
            ],
        }
        if usage is not None:
            body["choices"] = []
            body["usage"] = usage
        return json.dumps(body)

    stream = [
        ChatCompletionChunk.model_validate_json(
            payload({"role": "assistant", "content": None})
        ),
        ChatCompletionChunk.model_validate_json(
            payload(
                {
                    "tool_calls": [
                        {
                            "index": 0,
                            "id": "call_sse",
                            "type": "function",
                            "function": {"name": "get_weather", "arguments": ""},
                        }
                    ]
                }
            )
        ),
        ChatCompletionChunk.model_validate_json(
            payload(
                {"tool_calls": [{"index": 0, "function": {"arguments": CITY_ARGS}}]},
                finish_reason="tool_calls",
            )
        ),
        ChatCompletionChunk.model_validate_json(
            payload(
                {},
                usage={
                    "prompt_tokens": 41,
                    "completion_tokens": 17,
                    "total_tokens": 58,
                },
            )
        ),
    ]

    aggregated = chat_completion_chunks_aggregator.aggregate(stream)

    assert aggregated is not None
    assert _message(aggregated)["tool_calls"] == [
        {
            "id": "call_sse",
            "type": "function",
            "function": {"name": "get_weather", "arguments": CITY_ARGS},
        }
    ]
    assert aggregated.model_dump()["usage"]["total_tokens"] == 58


def test_aggregate__fragments_arrive_out_of_index_order__ordered_by_index() -> None:
    """`index` decides both identity and order, not the order chunks arrive in."""
    stream = [
        _chunk(
            [
                (
                    0,
                    None,
                    _delta(
                        tool_calls=[
                            _tool_call(
                                1,
                                call_id="call_b",
                                name="second_fn",
                                arguments='{"b": ',
                            ),
                            _tool_call(
                                0,
                                call_id="call_a",
                                name="first_fn",
                                arguments='{"a":',
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
                            _tool_call(1, arguments="2}"),
                            _tool_call(0, arguments="1}"),
                        ]
                    ),
                )
            ]
        ),
        _chunk([(0, "tool_calls", _delta())]),
    ]

    aggregated = chat_completion_chunks_aggregator.aggregate(stream)

    assert aggregated is not None
    assert _message(aggregated)["tool_calls"] == [
        {
            "id": "call_a",
            "function": {"name": "first_fn", "arguments": '{"a":1}'},
        },
        {
            "id": "call_b",
            "function": {"name": "second_fn", "arguments": '{"b": 2}'},
        },
    ]


def test_aggregate__call_id_arrives_after_its_arguments__still_reported() -> None:
    stream = [
        _chunk(
            [
                (
                    0,
                    None,
                    _delta(
                        tool_calls=[
                            _tool_call(0, name="get_weather", arguments=CITY_ARGS)
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
                            _tool_call(0, call_id="call_late", call_type="function")
                        ]
                    ),
                )
            ]
        ),
        _chunk([(0, "stop", _delta())]),
    ]

    aggregated = chat_completion_chunks_aggregator.aggregate(stream)

    assert aggregated is not None
    assert _message(aggregated)["tool_calls"] == [
        {
            "function": {"name": "get_weather", "arguments": CITY_ARGS},
            "id": "call_late",
            "type": "function",
        }
    ]
