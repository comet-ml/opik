from typing import Any, List, Optional

from cohere.types import (
    ChatContentDeltaEventDelta,
    ChatContentDeltaEventDeltaMessage,
    ChatContentDeltaEventDeltaMessageContent,
    ChatMessageEndEventDelta,
    ChatToolCallDeltaEventDelta,
    ChatToolCallDeltaEventDeltaMessage,
    ChatToolCallDeltaEventDeltaMessageToolCalls,
    ChatToolCallDeltaEventDeltaMessageToolCallsFunction,
    ChatToolCallStartEventDelta,
    ChatToolCallStartEventDeltaMessage,
    ChatToolPlanDeltaEventDelta,
    ChatToolPlanDeltaEventDeltaMessage,
    ToolCallV2,
    ToolCallV2Function,
)
from cohere.v2.types import (
    ContentDeltaV2ChatStreamResponse,
    MessageEndV2ChatStreamResponse,
    MessageStartV2ChatStreamResponse,
    ToolCallDeltaV2ChatStreamResponse,
    ToolCallEndV2ChatStreamResponse,
    ToolCallStartV2ChatStreamResponse,
    ToolPlanDeltaV2ChatStreamResponse,
)

from opik.integrations.cohere import chat_stream_aggregator


def _message_start() -> MessageStartV2ChatStreamResponse:
    return MessageStartV2ChatStreamResponse(type="message-start", id="c1")


def _message_end(
    finish_reason: str,
) -> MessageEndV2ChatStreamResponse:
    return MessageEndV2ChatStreamResponse(
        type="message-end",
        id="c1",
        delta=ChatMessageEndEventDelta(finish_reason=finish_reason),
    )


def _text_delta(text: str) -> ContentDeltaV2ChatStreamResponse:
    return ContentDeltaV2ChatStreamResponse(
        type="content-delta",
        delta=ChatContentDeltaEventDelta(
            message=ChatContentDeltaEventDeltaMessage(
                content=ChatContentDeltaEventDeltaMessageContent(text=text)
            )
        ),
    )


def _tool_plan_delta(tool_plan: str) -> ToolPlanDeltaV2ChatStreamResponse:
    return ToolPlanDeltaV2ChatStreamResponse(
        type="tool-plan-delta",
        delta=ChatToolPlanDeltaEventDelta(
            message=ChatToolPlanDeltaEventDeltaMessage(tool_plan=tool_plan)
        ),
    )


def _tool_call_start(
    index: int, call_id: str, name: str
) -> ToolCallStartV2ChatStreamResponse:
    return ToolCallStartV2ChatStreamResponse(
        type="tool-call-start",
        index=index,
        delta=ChatToolCallStartEventDelta(
            message=ChatToolCallStartEventDeltaMessage(
                tool_calls=ToolCallV2(
                    id=call_id,
                    type="function",
                    function=ToolCallV2Function(name=name, arguments=""),
                )
            )
        ),
    )


def _tool_call_delta(index: int, arguments: str) -> ToolCallDeltaV2ChatStreamResponse:
    return ToolCallDeltaV2ChatStreamResponse(
        type="tool-call-delta",
        index=index,
        delta=ChatToolCallDeltaEventDelta(
            message=ChatToolCallDeltaEventDeltaMessage(
                tool_calls=ChatToolCallDeltaEventDeltaMessageToolCalls(
                    function=ChatToolCallDeltaEventDeltaMessageToolCallsFunction(
                        arguments=arguments
                    )
                )
            )
        ),
    )


def _tool_call_end(index: int) -> ToolCallEndV2ChatStreamResponse:
    return ToolCallEndV2ChatStreamResponse(type="tool-call-end", index=index)


def _aggregate(events: List[Any]) -> Optional[Any]:
    return chat_stream_aggregator.aggregate(events)


def test__tool_call_turn__tool_plan_and_tool_calls_are_aggregated():
    # The turn from issue #9101: no text at all, only a plan and one call.
    events = [
        _message_start(),
        _tool_plan_delta("I will look it up."),
        _tool_call_start(0, "call_a", "get_weather"),
        _tool_call_delta(0, '{"city": "Paris"}'),
        _tool_call_end(0),
        _message_end("TOOL_CALL"),
    ]

    result = _aggregate(events)

    assert result is not None
    assert result.finish_reason == "TOOL_CALL"
    assert result.message == {
        "role": "assistant",
        "content": [{"type": "text", "text": ""}],
        "tool_plan": "I will look it up.",
        "tool_calls": [
            {
                "id": "call_a",
                "type": "function",
                "function": {
                    "name": "get_weather",
                    "arguments": '{"city": "Paris"}',
                },
            }
        ],
    }


def test__plain_text_turn__no_tool_keys_are_added():
    events = [
        _message_start(),
        _text_delta("Hello, "),
        _text_delta("world"),
        _message_end("COMPLETE"),
    ]

    result = _aggregate(events)

    assert result is not None
    assert result.message == {
        "role": "assistant",
        "content": [{"type": "text", "text": "Hello, world"}],
    }


def test__multiple_tool_calls__arguments_concatenated_per_index():
    events = [
        _message_start(),
        _tool_plan_delta("I will check "),
        _tool_plan_delta("both cities."),
        _tool_call_start(0, "call_a", "get_weather"),
        _tool_call_start(1, "call_b", "get_time"),
        _tool_call_delta(1, '{"city": '),
        _tool_call_delta(0, '{"city": "Paris"}'),
        _tool_call_delta(1, '"Paris"}'),
        _tool_call_end(0),
        _tool_call_end(1),
        _message_end("TOOL_CALL"),
    ]

    result = _aggregate(events)

    assert result is not None
    assert result.message["tool_plan"] == "I will check both cities."
    assert result.message["tool_calls"] == [
        {
            "id": "call_a",
            "type": "function",
            "function": {"name": "get_weather", "arguments": '{"city": "Paris"}'},
        },
        {
            "id": "call_b",
            "type": "function",
            "function": {"name": "get_time", "arguments": '{"city": "Paris"}'},
        },
    ]


def test__tool_call_delta_without_start__arguments_are_kept():
    events = [
        _message_start(),
        _tool_call_delta(0, '{"city": "Paris"}'),
        _message_end("TOOL_CALL"),
    ]

    result = _aggregate(events)

    assert result is not None
    assert result.message["tool_calls"] == [
        {
            "id": None,
            "type": "function",
            "function": {"name": None, "arguments": '{"city": "Paris"}'},
        }
    ]
