import asyncio
import json
from typing import Any, Dict, List, Optional
from unittest import mock

from mistralai.models.completionevent import CompletionEvent

from opik.integrations.mistral import (
    chat_completion_chunks_aggregator,
    stream_patchers,
)

CREATED = 1750000000
CHUNK_ID = "stream-1"
MODEL = "mistral-large-latest"

FIRST_ARGS = '{"city":"Paris"}'
SECOND_ARGS = '{"tz":"CET"}'
PART_ARGS = '{"city"'
TAIL_ARGS = ':"Paris"}'

CALL_A = {
    "id": "call_a",
    "type": "function",
    "function": {"name": "get_weather", "arguments": FIRST_ARGS},
}
CALL_B = {
    "id": "call_b",
    "type": "function",
    "function": {"name": "get_time", "arguments": SECOND_ARGS},
}


def _event(
    delta: Dict[str, Any],
    finish_reason: Optional[str] = None,
    usage: Optional[Dict[str, Any]] = None,
    choices: Optional[List[Dict[str, Any]]] = None,
) -> CompletionEvent:
    """Parse a chunk the way the client parses a ``data:`` line.

    Every fixture here goes through ``model_validate_json`` on purpose: the
    defect is about what the SDK hands the aggregator, so a hand-built object
    could produce a shape the client would reject before it got here.
    """
    body = {
        "data": {
            "id": CHUNK_ID,
            "model": MODEL,
            "object": "chat.completion.chunk",
            "created": CREATED,
            "usage": usage,
            "choices": (
                choices
                if choices is not None
                else [{"index": 0, "delta": delta, "finish_reason": finish_reason}]
            ),
        }
    }
    return CompletionEvent.model_validate_json(json.dumps(body))


def _message(aggregated: Any) -> Dict[str, Any]:
    return aggregated.model_dump()["choices"][0]["message"]


def test_aggregate__two_calls_in_separate_chunks_without_index__keeps_both() -> None:
    stream = [
        _event({"role": "assistant", "tool_calls": [CALL_A]}),
        _event({"tool_calls": [CALL_B]}),
        _event({}, finish_reason="tool_calls"),
    ]

    aggregated = chat_completion_chunks_aggregator.aggregate(stream)

    assert aggregated is not None
    assert _message(aggregated)["tool_calls"] == [
        {
            "id": "call_a",
            "type": "function",
            "index": 0,
            "function": {"name": "get_weather", "arguments": FIRST_ARGS},
        },
        {
            "id": "call_b",
            "type": "function",
            "index": 0,
            "function": {"name": "get_time", "arguments": SECOND_ARGS},
        },
    ]


def test_aggregate__two_calls_in_one_chunk_without_index__keeps_both() -> None:
    stream = [
        _event({"role": "assistant", "tool_calls": [CALL_A, CALL_B]}),
        _event({}, finish_reason="tool_calls"),
    ]

    aggregated = chat_completion_chunks_aggregator.aggregate(stream)

    assert aggregated is not None
    assert _message(aggregated)["tool_calls"] == [
        {
            "id": "call_a",
            "type": "function",
            "index": 0,
            "function": {"name": "get_weather", "arguments": FIRST_ARGS},
        },
        {
            "id": "call_b",
            "type": "function",
            "index": 0,
            "function": {"name": "get_time", "arguments": SECOND_ARGS},
        },
    ]


def test_aggregate__fragments_sharing_a_sent_index__merge_into_one_call() -> None:
    """The other half of the identity rule: same sent index is one call."""
    stream = [
        _event(
            {
                "role": "assistant",
                "tool_calls": [
                    {
                        "id": "call_a",
                        "type": "function",
                        "index": 0,
                        "function": {"name": "get_weather", "arguments": PART_ARGS},
                    }
                ],
            }
        ),
        _event(
            {
                "tool_calls": [
                    {
                        "id": "call_a",
                        "type": "function",
                        "index": 0,
                        "function": {"name": "get_weather", "arguments": TAIL_ARGS},
                    }
                ]
            }
        ),
        _event(
            {
                "tool_calls": [
                    {
                        "id": "call_b",
                        "type": "function",
                        "index": 1,
                        "function": {"name": "get_time", "arguments": SECOND_ARGS},
                    }
                ]
            }
        ),
        _event({}, finish_reason="tool_calls"),
    ]

    aggregated = chat_completion_chunks_aggregator.aggregate(stream)

    assert aggregated is not None
    assert _message(aggregated)["tool_calls"] == [
        {
            "id": "call_a",
            "type": "function",
            "index": 0,
            "function": {"name": "get_weather", "arguments": FIRST_ARGS},
        },
        {
            "id": "call_b",
            "type": "function",
            "index": 1,
            "function": {"name": "get_time", "arguments": SECOND_ARGS},
        },
    ]


def test_aggregate__sent_index_and_no_id__continues_the_same_call() -> None:
    """Where no id is present, ``index`` is the only identity signal there is.

    The expected list was read off ``4546b5e66`` and ``fcd2f055e``, which agree:
    this stream is not what the fix changes, it is what the fix must not change.
    """
    stream = [
        _event(
            {
                "role": "assistant",
                "tool_calls": [
                    {
                        "index": 0,
                        "function": {"name": "get_weather", "arguments": PART_ARGS},
                    }
                ],
            }
        ),
        _event(
            {
                "tool_calls": [
                    {
                        "index": 0,
                        "function": {"name": "get_weather", "arguments": TAIL_ARGS},
                    }
                ]
            }
        ),
        _event(
            {
                "tool_calls": [
                    {
                        "index": 1,
                        "function": {"name": "get_time", "arguments": SECOND_ARGS},
                    }
                ]
            }
        ),
        _event({}, finish_reason="tool_calls"),
    ]

    aggregated = chat_completion_chunks_aggregator.aggregate(stream)

    assert aggregated is not None
    assert _message(aggregated)["tool_calls"] == [
        {
            "id": "null",
            "type": None,
            "index": 0,
            "function": {"name": "get_weather", "arguments": FIRST_ARGS},
        },
        {
            "id": "null",
            "type": None,
            "index": 1,
            "function": {"name": "get_time", "arguments": SECOND_ARGS},
        },
    ]


def test_aggregate__unindexed_call_then_sent_index_zero__keeps_both() -> None:
    """A provider index must not land on a slot this module invented itself.

    ``ToolCall.index`` defaults to ``0``, so the first fragment here and the
    second one's sent index are the same number by the time they reach the
    aggregator. Before the fix they addressed one key, and the second call was
    appended to the first one's arguments instead of opening its own call.
    """
    stream = [
        _event({"role": "assistant", "tool_calls": [CALL_A]}),
        _event({"tool_calls": [dict(CALL_B, index=0)]}),
        _event({}, finish_reason="tool_calls"),
    ]

    aggregated = chat_completion_chunks_aggregator.aggregate(stream)

    assert aggregated is not None
    calls = _message(aggregated)["tool_calls"]
    assert [call["id"] for call in calls] == ["call_b", "call_a"], calls
    assert [call["function"]["arguments"] for call in calls] == [
        SECOND_ARGS,
        FIRST_ARGS,
    ], calls


def test_aggregate__unindexed_call_then_same_id_with_sent_index__stays_one_call() -> (
    None
):
    """The mirror image of the case above: the id is seen first, the index later.

    The first piece opens ``call_a`` on an invented slot because no index was
    sent. The piece that continues it does send one, and reading that index first
    moved the rest of the arguments into a second recorded call.
    """
    stream = [
        _event(
            {
                "role": "assistant",
                "tool_calls": [
                    {
                        "id": "call_a",
                        "type": "function",
                        "function": {"name": "get_weather", "arguments": PART_ARGS},
                    }
                ],
            }
        ),
        _event(
            {
                "tool_calls": [
                    {
                        "id": "call_a",
                        "type": "function",
                        "index": 0,
                        "function": {"name": "get_weather", "arguments": TAIL_ARGS},
                    }
                ]
            }
        ),
        _event({}, finish_reason="tool_calls"),
    ]

    aggregated = chat_completion_chunks_aggregator.aggregate(stream)

    assert aggregated is not None
    calls = _message(aggregated)["tool_calls"]
    assert [call["id"] for call in calls] == ["call_a"], calls
    assert [call["function"]["arguments"] for call in calls] == [FIRST_ARGS], calls


def test_aggregate__explicit_null_index__keeps_both_calls() -> None:
    """``index: null`` is the one shape the old ``is not None`` test did catch."""
    stream = [
        _event({"role": "assistant", "tool_calls": [dict(CALL_A, index=None)]}),
        _event({"tool_calls": [dict(CALL_B, index=None)]}),
        _event({}, finish_reason="tool_calls"),
    ]

    aggregated = chat_completion_chunks_aggregator.aggregate(stream)

    assert aggregated is not None
    calls = _message(aggregated)["tool_calls"]
    assert [c["function"]["name"] for c in calls] == ["get_weather", "get_time"]
    assert [c["function"]["arguments"] for c in calls] == [FIRST_ARGS, SECOND_ARGS]


def test_aggregate__same_id_repeated_without_index__stays_one_call() -> None:
    """A fix that keys every fragment as new would split this into two calls."""
    stream = [
        _event(
            {
                "role": "assistant",
                "tool_calls": [
                    {
                        "id": "call_a",
                        "type": "function",
                        "function": {"name": "get_weather", "arguments": PART_ARGS},
                    }
                ],
            }
        ),
        _event(
            {
                "tool_calls": [
                    {
                        "id": "call_a",
                        "type": "function",
                        "function": {"name": "get_weather", "arguments": TAIL_ARGS},
                    }
                ]
            }
        ),
        _event({}, finish_reason="tool_calls"),
    ]

    aggregated = chat_completion_chunks_aggregator.aggregate(stream)

    assert aggregated is not None
    assert _message(aggregated)["tool_calls"] == [
        {
            "id": "call_a",
            "type": "function",
            "index": 0,
            "function": {"name": "get_weather", "arguments": FIRST_ARGS},
        }
    ]


def test_aggregate__object_arguments_without_index__keeps_both_calls() -> None:
    """``FunctionCall.arguments`` is a union; two dicts merged is a TypeError."""
    first = {
        "id": "call_c",
        "type": "function",
        "function": {"name": "f", "arguments": {"a": 1}},
    }
    second = {
        "id": "call_d",
        "type": "function",
        "function": {"name": "g", "arguments": {"b": 2}},
    }
    stream = [
        _event({"role": "assistant", "tool_calls": [first]}),
        _event({"tool_calls": [second]}),
        _event({}, finish_reason="tool_calls"),
    ]

    aggregated = chat_completion_chunks_aggregator.aggregate(stream)

    assert aggregated is not None, "aggregate() aborted instead of reporting both calls"
    calls = _message(aggregated)["tool_calls"]
    assert [c["function"]["arguments"] for c in calls] == [{"a": 1}, {"b": 2}]


def test_aggregate__index_sent__output_unchanged() -> None:
    """Streams that do send an index keep their previous aggregated shape."""
    stream = [
        _event({"role": "assistant", "tool_calls": [dict(CALL_A, index=0)]}),
        _event({"tool_calls": [dict(CALL_B, index=1)]}),
        _event({}, finish_reason="tool_calls"),
    ]

    aggregated = chat_completion_chunks_aggregator.aggregate(stream)

    assert aggregated is not None
    assert aggregated.model_dump() == {
        "choices": [
            {
                "finish_reason": "tool_calls",
                "index": 0,
                "message": {
                    "content": "",
                    "role": "assistant",
                    "tool_calls": [
                        {
                            "function": {
                                "arguments": '{"city":"Paris"}',
                                "name": "get_weather",
                            },
                            "id": "call_a",
                            "index": 0,
                            "type": "function",
                        },
                        {
                            "function": {
                                "arguments": '{"tz":"CET"}',
                                "name": "get_time",
                            },
                            "id": "call_b",
                            "index": 1,
                            "type": "function",
                        },
                    ],
                },
            }
        ],
        "created": 1750000000,
        "id": "stream-1",
        "model": "mistral-large-latest",
        "object": "chat.completion.chunk",
        "usage": None,
    }


def test_aggregate__text_only_stream__output_unchanged() -> None:
    stream = [
        _event({"role": "assistant", "content": "Hello"}),
        _event({"content": " world"}),
        _event({}, finish_reason="stop"),
        _event(
            {}, usage={"prompt_tokens": 5, "completion_tokens": 2, "total_tokens": 7}
        ),
    ]

    aggregated = chat_completion_chunks_aggregator.aggregate(stream)

    assert aggregated is not None
    assert aggregated.model_dump() == {
        "choices": [
            {
                "finish_reason": "stop",
                "index": 0,
                "message": {"content": "Hello world", "role": "assistant"},
            }
        ],
        "created": 1750000000,
        "id": "stream-1",
        "model": "mistral-large-latest",
        "object": "chat.completion.chunk",
        "usage": {"completion_tokens": 2, "prompt_tokens": 5, "total_tokens": 7},
    }


_TWO_CALL_STREAM = [
    _event({"role": "assistant", "tool_calls": [CALL_A]}),
    _event({"tool_calls": [CALL_B]}),
    _event({}, finish_reason="tool_calls"),
]


def _assert_both_calls_reach_the_callback(callback: mock.Mock) -> None:
    """What the finished span is handed, read off the callback's output."""
    callback.assert_called_once()
    assert callback.call_args.kwargs["capture_output"] is True
    output = callback.call_args.kwargs["output"]
    assert output is not None, "the stream finalizer reported no output"
    tool_calls = _message(output)["tool_calls"]
    assert [call["id"] for call in tool_calls] == ["call_a", "call_b"], tool_calls
    assert [call["function"]["arguments"] for call in tool_calls] == [
        FIRST_ARGS,
        SECOND_ARGS,
    ], tool_calls


def _patched_stream(events: List[CompletionEvent], callback: mock.Mock) -> Any:
    """A stand-in for ``client.chat.stream(...)``, with opik's patcher installed.

    ``opik_tracker.track_mistral_client`` passes ``aggregate`` as the stream's
    ``generations_aggregator``, and the patcher drives the stream's own
    ``__next__``/``__anext__`` (a mistralai event stream is its own iterator), so
    this is built the same way without a credential or an HTTP server.
    """

    class _EventStream:
        def __init__(self) -> None:
            self._remaining = list(events)

        def __next__(self) -> CompletionEvent:
            if not self._remaining:
                raise StopIteration
            return self._remaining.pop(0)

        async def __anext__(self) -> CompletionEvent:
            if not self._remaining:
                raise StopAsyncIteration
            return self._remaining.pop(0)

    stream = _EventStream()
    stream_patchers.patch_sync_event_stream(
        stream,
        span_to_end=None,
        trace_to_end=None,
        generations_aggregator=chat_completion_chunks_aggregator.aggregate,
        finally_callback=callback,
    )
    stream_patchers.patch_async_event_stream(
        stream,
        span_to_end=None,
        trace_to_end=None,
        generations_aggregator=chat_completion_chunks_aggregator.aggregate,
        finally_callback=callback,
    )
    return stream


def test_patched_stream__two_calls_without_index__both_reach_the_span_output() -> None:
    """The defect was invisible one level up too: the trace showed one call."""
    callback = mock.Mock()
    stream = _patched_stream(_TWO_CALL_STREAM, callback)

    assert len(list(stream)) == len(_TWO_CALL_STREAM)

    _assert_both_calls_reach_the_callback(callback)


def test_patched_async_stream__two_calls_without_index__both_reach_the_output() -> None:
    callback = mock.Mock()
    stream = _patched_stream(_TWO_CALL_STREAM, callback)

    async def _drain() -> int:
        received = 0
        async for _ in stream:
            received += 1
        return received

    assert asyncio.run(_drain()) == len(_TWO_CALL_STREAM)

    _assert_both_calls_reach_the_callback(callback)
