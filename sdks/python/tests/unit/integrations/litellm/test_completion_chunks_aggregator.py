import uuid
from typing import Any, Dict, List, Optional

import litellm.types.utils
from litellm.types.utils import ChatCompletionDeltaToolCall, Delta, Function

from opik.integrations.litellm import completion_chunks_aggregator


def _random(prefix: str) -> str:
    return f"{prefix}_{uuid.uuid4().hex[:8]}"


def _chunk(
    *tool_calls: ChatCompletionDeltaToolCall,
    content: Optional[str] = None,
    finish_reason: Optional[str] = None,
) -> litellm.types.utils.ModelResponseStream:
    delta = Delta(
        role="assistant",
        content=content,
        tool_calls=list(tool_calls) if tool_calls else None,
    )
    return litellm.types.utils.ModelResponseStream(
        id="chatcmpl-1",
        model="gpt-4o-mini",
        choices=[
            litellm.types.utils.StreamingChoices(
                index=0, delta=delta, finish_reason=finish_reason
            )
        ],
    )


def _call(
    index: int,
    arguments: str,
    id: Optional[str] = None,
    name: Optional[str] = None,
) -> ChatCompletionDeltaToolCall:
    return ChatCompletionDeltaToolCall(
        id=id,
        type="function",
        index=index,
        function=Function(name=name, arguments=arguments),
    )


def _message(chunks: List[Any]) -> Dict[str, Any]:
    aggregated = completion_chunks_aggregator.aggregate(chunks)
    assert aggregated is not None
    return aggregated.choices[0].message.model_dump()


def _expected_message(content: str, tool_calls: Optional[List[Dict[str, Any]]]):
    return {
        "content": content,
        "role": "assistant",
        "tool_calls": tool_calls,
        "function_call": None,
        "provider_specific_fields": None,
    }


def _expected_call(id: str, name: str, arguments: str) -> Dict[str, Any]:
    return {
        "id": id,
        "type": "function",
        "function": {"name": name, "arguments": arguments},
    }


def test_aggregate__interleaved_tool_calls__are_kept_apart_by_index():
    id_a, id_b = _random("call"), _random("call")
    name_a, name_b = _random("tool"), _random("tool")
    key_a, key_b = _random("key"), _random("key")
    value_a, value_b = _random("value"), _random("value")
    chunks = [
        _chunk(_call(0, "", id=id_a, name=name_a), _call(1, "", id=id_b, name=name_b)),
        _chunk(_call(0, f'{{"{key_a}": ')),
        _chunk(_call(1, f'{{"{key_b}": ')),
        _chunk(_call(1, f'"{value_b}"}}')),
        _chunk(_call(0, f'"{value_a}"}}'), finish_reason="tool_calls"),
    ]

    assert _message(chunks) == _expected_message(
        "",
        [
            _expected_call(id_a, name_a, f'{{"{key_a}": "{value_a}"}}'),
            _expected_call(id_b, name_b, f'{{"{key_b}": "{value_b}"}}'),
        ],
    )


def test_aggregate__id_and_name_only_on_first_fragment__arguments_are_joined():
    call_id, name = _random("call"), _random("tool")
    pieces = [_random("piece") for _ in range(4)]
    chunks = [_chunk(_call(0, "", id=call_id, name=name))]
    chunks += [_chunk(_call(0, piece)) for piece in pieces]

    assert _message(chunks) == _expected_message(
        "", [_expected_call(call_id, name, "".join(pieces))]
    )


def test_aggregate__text_and_tool_call__both_kept():
    text_parts = [_random("text"), _random("text")]
    call_id, name, arguments = _random("call"), _random("tool"), _random("args")
    chunks = [
        _chunk(content=text_parts[0]),
        _chunk(content=text_parts[1]),
        _chunk(_call(0, arguments, id=call_id, name=name), finish_reason="tool_calls"),
    ]

    assert _message(chunks) == _expected_message(
        "".join(text_parts), [_expected_call(call_id, name, arguments)]
    )


def test_aggregate__text_only_stream__has_no_tool_calls():
    text_parts = [_random("text"), _random("text")]

    message = _message([_chunk(content=text_parts[0]), _chunk(content=text_parts[1])])

    assert message == _expected_message("".join(text_parts), None)
