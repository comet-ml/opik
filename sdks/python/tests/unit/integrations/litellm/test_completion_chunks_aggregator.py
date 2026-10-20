from typing import Any, Dict, Optional

import litellm.types.utils
from litellm.types.utils import ChatCompletionDeltaToolCall, Delta, Function

from opik.integrations.litellm import completion_chunks_aggregator


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


def _message(chunks: list) -> Dict[str, Any]:
    aggregated = completion_chunks_aggregator.aggregate(chunks)
    assert aggregated is not None
    return aggregated.choices[0].message.model_dump()


def test_aggregate__streamed_tool_calls__are_kept_apart_by_index():
    chunks = [
        _chunk(
            _call(0, "", id="call_a", name="first"),
            _call(1, "", id="call_b", name="second"),
        ),
        _chunk(_call(0, '{"x":')),
        _chunk(_call(1, '{"y":')),
        _chunk(_call(1, "2}")),
        _chunk(_call(0, "1}"), finish_reason="tool_calls"),
    ]

    message = _message(chunks)

    assert [
        (call["id"], call["function"]["name"], call["function"]["arguments"])
        for call in message["tool_calls"]
    ] == [
        ("call_a", "first", '{"x":1}'),
        ("call_b", "second", '{"y":2}'),
    ]


def test_aggregate__text_only_stream__has_no_tool_calls():
    message = _message([_chunk(content="Hel"), _chunk(content="lo")])

    assert message["content"] == "Hello"
    assert not message.get("tool_calls")
