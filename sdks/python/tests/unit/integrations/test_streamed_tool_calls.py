from typing import Optional

import pydantic
from openai.types.chat.chat_completion_chunk import (
    ChoiceDeltaToolCall,
    ChoiceDeltaToolCallFunction,
)

from opik.integrations import _streamed_tool_calls


class _FloatIndexToolCall(pydantic.BaseModel):
    """Shape of Together's streamed tool call, whose index is typed as a float."""

    index: float
    id: Optional[str] = None
    type: Optional[str] = None
    function: dict


def _fragment(index=None, call_id=None, name=None, arguments=None):
    """A streamed tool-call fragment; unset fields stay absent, as in a real stream."""
    fields = {
        "function": ChoiceDeltaToolCallFunction.model_construct(
            **{
                key: value
                for key, value in (("name", name), ("arguments", arguments))
                if value is not None
            }
        )
    }
    if index is not None:
        fields["index"] = index
    if call_id is not None:
        fields["id"] = call_id
        fields["type"] = "function"
    return ChoiceDeltaToolCall.model_construct(**fields)


def test_add__continuation_without_index__joins_the_call_with_the_same_id():
    tool_calls = _streamed_tool_calls.StreamedToolCalls()

    tool_calls.add(_fragment(0, "call_1", "get_weather", '{"city": '))
    tool_calls.add(_fragment(call_id="call_1", arguments='"Paris"}'))

    assert tool_calls.to_list() == [
        {
            "id": "call_1",
            "type": "function",
            "function": {"name": "get_weather", "arguments": '{"city": "Paris"}'},
        }
    ]


def test_add__float_indices__keep_calls_apart_and_continue_by_index():
    tool_calls = _streamed_tool_calls.StreamedToolCalls()

    tool_calls.add(
        _FloatIndexToolCall(
            index=0.0,
            id="call_1",
            type="function",
            function={"name": "get_weather", "arguments": '{"city": "Paris"}'},
        )
    )
    tool_calls.add(
        _FloatIndexToolCall(
            index=1.0,
            id="call_2",
            type="function",
            function={"name": "get_time", "arguments": '{"tz": '},
        )
    )
    tool_calls.add(_FloatIndexToolCall(index=1.0, function={"arguments": '"CET"}'}))

    assert tool_calls.to_list() == [
        {
            "id": "call_1",
            "type": "function",
            "function": {"name": "get_weather", "arguments": '{"city": "Paris"}'},
        },
        {
            "id": "call_2",
            "type": "function",
            "function": {"name": "get_time", "arguments": '{"tz": "CET"}'},
        },
    ]


def test_add__fragment_without_index_or_known_id__opens_a_new_call():
    tool_calls = _streamed_tool_calls.StreamedToolCalls()

    tool_calls.add(_fragment(0, "call_1", "get_weather", "{}"))
    tool_calls.add(_fragment(call_id="call_2", name="get_time", arguments="{}"))

    assert tool_calls.to_list() == [
        {
            "id": "call_1",
            "type": "function",
            "function": {"name": "get_weather", "arguments": "{}"},
        },
        {
            "id": "call_2",
            "type": "function",
            "function": {"name": "get_time", "arguments": "{}"},
        },
    ]


def test_to_list__fragments_arriving_out_of_index_order__returns_calls_by_index():
    tool_calls = _streamed_tool_calls.StreamedToolCalls()

    tool_calls.add(_fragment(1, "call_2", "get_time", '{"tz": '))
    tool_calls.add(_fragment(0, "call_1", "get_weather", "{}"))
    tool_calls.add(_fragment(1, arguments='"CET"}'))

    assert tool_calls.to_list() == [
        {
            "id": "call_1",
            "type": "function",
            "function": {"name": "get_weather", "arguments": "{}"},
        },
        {
            "id": "call_2",
            "type": "function",
            "function": {"name": "get_time", "arguments": '{"tz": "CET"}'},
        },
    ]
