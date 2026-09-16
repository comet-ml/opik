import json
from typing import Any, Dict, List, Optional

from mistralai.models.completionevent import CompletionEvent

from opik.integrations.mistral import chat_completion_chunks_aggregator

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
    names = [c["function"]["name"] for c in _message(aggregated)["tool_calls"]]
    assert names == ["get_weather", "get_time"]


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
