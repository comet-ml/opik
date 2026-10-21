from typing import Optional

from openai.types.chat.chat_completion_chunk import (
    ChoiceDeltaToolCall,
    ChoiceDeltaToolCallFunction,
)
import pydantic

from opik.integrations import _streamed_tool_calls


class _FloatIndexFunction(pydantic.BaseModel):
    name: Optional[str] = None
    arguments: Optional[str] = None


class _FloatIndexToolCall(pydantic.BaseModel):
    """Stands in for providers that type a streamed tool call's ``index`` as a float."""

    index: float
    id: Optional[str] = None
    type: Optional[str] = None
    function: _FloatIndexFunction


def _together_fragment(index, call_id=None, name=None, arguments=None):
    return _FloatIndexToolCall(
        index=index,
        id=call_id,
        type="function" if call_id else None,
        function=_FloatIndexFunction(name=name, arguments=arguments),
    )


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
        _together_fragment(0.0, "call_1", "get_weather", '{"city": "Paris"}')
    )
    tool_calls.add(_together_fragment(1.0, "call_2", "get_time", '{"tz": '))
    tool_calls.add(_together_fragment(1.0, arguments='"CET"}'))

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


def test_add__fractional_index__does_not_merge_into_the_truncated_call():
    tool_calls = _streamed_tool_calls.StreamedToolCalls()

    tool_calls.add(
        _together_fragment(0.0, "call_1", "get_weather", '{"city": "Paris"}')
    )
    tool_calls.add(_together_fragment(0.5, "call_2", "get_time", '{"tz": "CET"}'))

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


def test_to_list__called_twice__does_not_change_the_result():
    tool_calls = _streamed_tool_calls.StreamedToolCalls()
    tool_calls.add(_fragment(0, "call_1", "get_weather", '{"city": '))
    tool_calls.add(_fragment(0, arguments='"Paris"}'))

    first = tool_calls.to_list()
    tool_calls.add(_fragment(0, arguments=" "))

    assert first[0]["function"]["arguments"] == '{"city": "Paris"}'
    assert tool_calls.to_list()[0]["function"]["arguments"] == '{"city": "Paris"} '
