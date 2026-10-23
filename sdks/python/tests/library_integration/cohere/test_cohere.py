import asyncio
import json

import httpx
import respx

import cohere
from cohere.types.chat_content_delta_event_delta import ChatContentDeltaEventDelta
from cohere.types.chat_content_delta_event_delta_message import (
    ChatContentDeltaEventDeltaMessage,
)
from cohere.types.chat_content_delta_event_delta_message_content import (
    ChatContentDeltaEventDeltaMessageContent,
)
from cohere.types.chat_message_end_event_delta import ChatMessageEndEventDelta
from cohere.types.chat_tool_call_delta_event_delta import ChatToolCallDeltaEventDelta
from cohere.types.chat_tool_call_delta_event_delta_message import (
    ChatToolCallDeltaEventDeltaMessage,
)
from cohere.types.chat_tool_call_delta_event_delta_message_tool_calls import (
    ChatToolCallDeltaEventDeltaMessageToolCalls,
)
from cohere.types.chat_tool_call_delta_event_delta_message_tool_calls_function import (
    ChatToolCallDeltaEventDeltaMessageToolCallsFunction,
)
from cohere.types.chat_tool_call_start_event_delta import ChatToolCallStartEventDelta
from cohere.types.chat_tool_call_start_event_delta_message import (
    ChatToolCallStartEventDeltaMessage,
)
from cohere.types.chat_tool_plan_delta_event_delta import ChatToolPlanDeltaEventDelta
from cohere.types.chat_tool_plan_delta_event_delta_message import (
    ChatToolPlanDeltaEventDeltaMessage,
)
from cohere.types.tool_call_v2 import ToolCallV2
from cohere.types.tool_call_v2function import ToolCallV2Function
from cohere.v2.types.v2chat_stream_response import (
    ContentDeltaV2ChatStreamResponse,
    MessageEndV2ChatStreamResponse,
    MessageStartV2ChatStreamResponse,
    ToolCallDeltaV2ChatStreamResponse,
    ToolCallStartV2ChatStreamResponse,
    ToolPlanDeltaV2ChatStreamResponse,
)

import opik
from opik.config import OPIK_PROJECT_DEFAULT_NAME
from opik.integrations.cohere import (
    chat_stream_aggregator,
    cohere_chat_decorator,
    opik_tracker,
    track_cohere,
)

from ... import llm_constants
from ...testlib import (
    ANY_BUT_NONE,
    ANY_DICT,
    SpanModel,
    TraceModel,
    assert_equal,
)

MODEL = llm_constants.COHERE_COMMAND


# These tests never reach Cohere: there is no API key in CI. The response is
# mocked at the HTTP layer rather than by replacing ``_raw_client`` so the
# library's own request building and response parsing still run — that is the
# only part of the provider contract a keyless suite can regress on. Swapping
# ``_raw_client`` hands the integration objects we built ourselves, and would
# keep passing even if Cohere changed how it parses a response.
BASE_URL = "https://api.cohere.com"
CHAT_ROUTE = "/v2/chat"

CONTENT = "Blue, due to Rayleigh scattering."


def _response_body(content: str = CONTENT) -> dict:
    """The JSON Cohere returns for a non-streaming v2 chat call."""
    return {
        "id": "c1",
        "finish_reason": "COMPLETE",
        "message": {
            "role": "assistant",
            "content": [{"type": "text", "text": content}],
        },
        "usage": {"tokens": {"input_tokens": 10, "output_tokens": 8}},
    }


def _sse(*events: dict) -> bytes:
    """Encode events as the ``text/event-stream`` body Cohere sends."""
    return "".join(f"data: {json.dumps(e)}\n\n" for e in events).encode()


def _stream_events(text: str = "Blue.", input_tokens: int = 5, output_tokens: int = 2):
    return (
        {"type": "message-start", "id": "c1"},
        {
            "type": "content-delta",
            "index": 0,
            "delta": {"message": {"content": {"text": text}}},
        },
        {
            "type": "message-end",
            "id": "c1",
            "delta": {
                "finish_reason": "COMPLETE",
                "usage": {
                    "tokens": {
                        "input_tokens": input_tokens,
                        "output_tokens": output_tokens,
                    }
                },
            },
        },
    )


def _content_delta(text=None, thinking=None):
    return ContentDeltaV2ChatStreamResponse(
        type="content-delta",
        index=0,
        delta=ChatContentDeltaEventDelta(
            message=ChatContentDeltaEventDeltaMessage(
                content=ChatContentDeltaEventDeltaMessageContent(
                    text=text, thinking=thinking
                )
            )
        ),
    )


TOOL_PLAN = "I will call get_weather for Paris."
TOOL_CALL = {
    "id": "call_1",
    "type": "function",
    "function": {"name": "get_weather", "arguments": '{"city": "Paris"}'},
}


def _tool_plan_delta(tool_plan: str):
    return ToolPlanDeltaV2ChatStreamResponse(
        type="tool-plan-delta",
        delta=ChatToolPlanDeltaEventDelta(
            message=ChatToolPlanDeltaEventDeltaMessage(tool_plan=tool_plan)
        ),
    )


def _tool_call_start(index: int, call_id: str, name: str):
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


def _tool_call_arguments_delta(index: int, arguments: str):
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


def _tool_call_stream_events():
    """The SSE events Cohere sends for a single streamed tool call."""
    return (
        {"type": "message-start", "id": "c1"},
        {
            "type": "tool-plan-delta",
            "delta": {"message": {"tool_plan": "I will call "}},
        },
        {
            "type": "tool-plan-delta",
            "delta": {"message": {"tool_plan": "get_weather for Paris."}},
        },
        {
            "type": "tool-call-start",
            "index": 0,
            "delta": {
                "message": {
                    "tool_calls": {
                        "id": "call_1",
                        "type": "function",
                        "function": {"name": "get_weather", "arguments": ""},
                    }
                }
            },
        },
        {
            "type": "tool-call-delta",
            "index": 0,
            "delta": {
                "message": {"tool_calls": {"function": {"arguments": '{"city": '}}}
            },
        },
        {
            "type": "tool-call-delta",
            "index": 0,
            "delta": {
                "message": {"tool_calls": {"function": {"arguments": '"Paris"}'}}}
            },
        },
        {"type": "tool-call-end", "index": 0},
        {
            "type": "message-end",
            "id": "c1",
            "delta": {
                "finish_reason": "TOOL_CALL",
                "usage": {"tokens": {"input_tokens": 12, "output_tokens": 9}},
            },
        },
    )


@respx.mock(base_url=BASE_URL)
def test_cohere_chat__happyflow(respx_mock, fake_backend):
    respx_mock.post(CHAT_ROUTE).mock(
        return_value=httpx.Response(200, json=_response_body())
    )
    client = cohere.ClientV2(api_key="fake-api-key")
    tracked = track_cohere(client)

    messages = [{"role": "user", "content": "Why is the sky blue?"}]
    response = tracked.chat(model=MODEL, messages=messages)
    opik.flush_tracker()

    assert response.message.content[0].text == "Blue, due to Rayleigh scattering."

    EXPECTED_TRACE_TREE = TraceModel(
        id=ANY_BUT_NONE,
        name="chat",
        input={"messages": messages},
        output=ANY_DICT,
        tags=["cohere"],
        metadata=ANY_DICT,
        start_time=ANY_BUT_NONE,
        end_time=ANY_BUT_NONE,
        last_updated_at=ANY_BUT_NONE,
        project_name=OPIK_PROJECT_DEFAULT_NAME,
        spans=[
            SpanModel(
                id=ANY_BUT_NONE,
                name="chat",
                input={"messages": messages},
                output=ANY_DICT,
                tags=["cohere"],
                metadata=ANY_DICT,
                start_time=ANY_BUT_NONE,
                end_time=ANY_BUT_NONE,
                project_name=OPIK_PROJECT_DEFAULT_NAME,
                type="llm",
                usage=ANY_DICT,
                model=MODEL,
                provider="cohere",
                spans=[],
                source="sdk",
            )
        ],
    )

    assert len(fake_backend.trace_trees) == 1
    assert_equal(EXPECTED_TRACE_TREE, fake_backend.trace_trees[0])


@respx.mock(base_url=BASE_URL)
def test_cohere_chat__usage_normalized_to_openai_token_names(respx_mock, fake_backend):
    """Cohere reports tokens.input_tokens / output_tokens, not prompt/completion."""
    respx_mock.post(CHAT_ROUTE).mock(
        return_value=httpx.Response(200, json=_response_body())
    )
    client = cohere.ClientV2(api_key="fake-api-key")
    tracked = track_cohere(client)

    tracked.chat(model=MODEL, messages=[{"role": "user", "content": "hi"}])
    opik.flush_tracker()

    usage = fake_backend.trace_trees[0].spans[0].usage
    assert usage["prompt_tokens"] == 10
    assert usage["completion_tokens"] == 8
    assert usage["total_tokens"] == 18


@respx.mock(base_url=BASE_URL)
def test_cohere_chat_stream__aggregates_into_one_span(respx_mock, fake_backend):
    """Drive the stream the way a user does, through the real SSE parser."""
    respx_mock.post(CHAT_ROUTE).mock(
        return_value=httpx.Response(
            200,
            headers={"Content-Type": "text/event-stream"},
            content=_sse(*_stream_events()),
        )
    )
    client = cohere.ClientV2(api_key="fake-api-key")
    tracked = track_cohere(client)

    collected = list(
        tracked.chat_stream(model=MODEL, messages=[{"role": "user", "content": "hi"}])
    )
    opik.flush_tracker()

    assert len(collected) == 3
    assert len(fake_backend.trace_trees) == 1
    span = fake_backend.trace_trees[0].spans[0]
    assert span.provider == "cohere"
    assert span.model == MODEL
    assert span.usage["prompt_tokens"] == 5
    assert span.usage["completion_tokens"] == 2


@respx.mock(base_url=BASE_URL)
def test_cohere_async_chat_and_chat_stream__happyflow(respx_mock, fake_backend):
    """AsyncClientV2 works today but nothing guarded it (awkoy, #8429)."""
    respx_mock.post(CHAT_ROUTE).mock(
        side_effect=[
            httpx.Response(200, json=_response_body()),
            httpx.Response(
                200,
                headers={"Content-Type": "text/event-stream"},
                content=_sse(*_stream_events()),
            ),
        ]
    )
    client = cohere.AsyncClientV2(api_key="fake-api-key")
    tracked = track_cohere(client)

    async def _exercise():
        response = await tracked.chat(
            model=MODEL, messages=[{"role": "user", "content": "hi"}]
        )
        chunks = [
            event
            async for event in tracked.chat_stream(
                model=MODEL, messages=[{"role": "user", "content": "hi"}]
            )
        ]
        return response, chunks

    response, chunks = asyncio.run(_exercise())
    opik.flush_tracker()

    assert response.message.content[0].text == CONTENT
    assert len(chunks) == 3
    assert len(fake_backend.trace_trees) == 2
    for tree in fake_backend.trace_trees:
        assert tree.spans[0].provider == "cohere"
        assert tree.spans[0].usage is not None


@respx.mock(base_url=BASE_URL)
def test_cohere_chat_stream__tool_call__kept_in_span_output(respx_mock, fake_backend):
    """A streamed tool call reaches the span, as it does through chat()."""
    respx_mock.post(CHAT_ROUTE).mock(
        return_value=httpx.Response(
            200,
            headers={"Content-Type": "text/event-stream"},
            content=_sse(*_tool_call_stream_events()),
        )
    )
    client = cohere.ClientV2(api_key="fake-api-key")
    tracked = track_cohere(client)

    list(tracked.chat_stream(model=MODEL, messages=[{"role": "user", "content": "hi"}]))
    opik.flush_tracker()

    span = fake_backend.trace_trees[0].spans[0]
    assert span.output["finish_reason"] == "TOOL_CALL"
    assert span.output["message"]["tool_plan"] == TOOL_PLAN
    assert span.output["message"]["tool_calls"] == [TOOL_CALL]


@respx.mock(base_url=BASE_URL)
def test_cohere_async_chat_stream__tool_call__kept_in_span_output(
    respx_mock, fake_backend
):
    respx_mock.post(CHAT_ROUTE).mock(
        return_value=httpx.Response(
            200,
            headers={"Content-Type": "text/event-stream"},
            content=_sse(*_tool_call_stream_events()),
        )
    )
    client = cohere.AsyncClientV2(api_key="fake-api-key")
    tracked = track_cohere(client)

    async def _exercise():
        return [
            event
            async for event in tracked.chat_stream(
                model=MODEL, messages=[{"role": "user", "content": "hi"}]
            )
        ]

    events = asyncio.run(_exercise())
    assert len(events) == len(_tool_call_stream_events())
    # the stream is passed through unchanged, so assert what was parsed and not
    # only how many events came back
    assert [event.type for event in events] == [
        event["type"] for event in _tool_call_stream_events()
    ]
    plan_deltas = [e for e in events if e.type == "tool-plan-delta"]
    assert [e.delta.message.tool_plan for e in plan_deltas] == [
        "I will call ",
        "get_weather for Paris.",
    ]
    start = next(e for e in events if e.type == "tool-call-start")
    assert start.index == 0
    assert start.delta.message.tool_calls.id == "call_1"
    assert start.delta.message.tool_calls.function.name == "get_weather"
    deltas = [e for e in events if e.type == "tool-call-delta"]
    assert [e.index for e in deltas] == [0, 0]
    assert (
        "".join(e.delta.message.tool_calls.function.arguments for e in deltas)
        == '{"city": "Paris"}'
    )
    opik.flush_tracker()

    span = fake_backend.trace_trees[0].spans[0]
    assert span.output["finish_reason"] == "TOOL_CALL"
    assert span.output["message"]["tool_calls"] == [TOOL_CALL]
    assert span.output["message"]["tool_plan"] == TOOL_PLAN


def test_aggregate__text_deltas__folded_into_one_message():
    aggregated = chat_stream_aggregator.aggregate(
        [
            MessageStartV2ChatStreamResponse(type="message-start", id="c9"),
            _content_delta(text="Blue, "),
            _content_delta(text="because of scattering."),
            MessageEndV2ChatStreamResponse(
                type="message-end",
                id="c9",
                delta=ChatMessageEndEventDelta(finish_reason="COMPLETE"),
            ),
        ]
    )

    assert aggregated.id == "c9"
    assert aggregated.finish_reason == "COMPLETE"
    assert aggregated.message["content"] == [
        {"type": "text", "text": "Blue, because of scattering."}
    ]


def test_aggregate__thinking_deltas__kept_separately_from_text():
    """Cohere streams reasoning in `thinking`, beside `text`."""
    aggregated = chat_stream_aggregator.aggregate(
        [
            MessageStartV2ChatStreamResponse(type="message-start", id="c9"),
            _content_delta(thinking="Rayleigh "),
            _content_delta(thinking="scattering."),
            _content_delta(text="Blue."),
            MessageEndV2ChatStreamResponse(
                type="message-end",
                id="c9",
                delta=ChatMessageEndEventDelta(finish_reason="COMPLETE"),
            ),
        ]
    )

    assert aggregated.message["content"] == [
        {"type": "thinking", "thinking": "Rayleigh scattering."},
        {"type": "text", "text": "Blue."},
    ]


def test_aggregate__no_thinking__thinking_block_absent():
    aggregated = chat_stream_aggregator.aggregate(
        [
            MessageStartV2ChatStreamResponse(type="message-start", id="c9"),
            _content_delta(text="Blue."),
        ]
    )

    assert all(block["type"] != "thinking" for block in aggregated.message["content"])


def test_aggregate__tool_call_events__reported_like_a_chat_response():
    """chat() reports the tool call and the plan on the message; the stream must too."""
    aggregated = chat_stream_aggregator.aggregate(
        [
            MessageStartV2ChatStreamResponse(type="message-start", id="c9"),
            _tool_plan_delta("I will call "),
            _tool_plan_delta("get_weather for Paris."),
            _tool_call_start(index=0, call_id="call_1", name="get_weather"),
            _tool_call_arguments_delta(index=0, arguments='{"city": '),
            _tool_call_arguments_delta(index=0, arguments='"Paris"}'),
            MessageEndV2ChatStreamResponse(
                type="message-end",
                id="c9",
                delta=ChatMessageEndEventDelta(finish_reason="TOOL_CALL"),
            ),
        ]
    )

    assert aggregated.finish_reason == "TOOL_CALL"
    assert aggregated.message["tool_plan"] == TOOL_PLAN
    assert aggregated.message["tool_calls"] == [TOOL_CALL]


def test_aggregate__parallel_tool_calls__kept_apart_by_index():
    aggregated = chat_stream_aggregator.aggregate(
        [
            MessageStartV2ChatStreamResponse(type="message-start", id="c9"),
            _tool_call_start(index=0, call_id="call_1", name="get_weather"),
            _tool_call_start(index=1, call_id="call_2", name="get_time"),
            _tool_call_arguments_delta(index=1, arguments='{"city": "Lyon"}'),
            _tool_call_arguments_delta(index=0, arguments='{"city": '),
            _tool_call_arguments_delta(index=0, arguments='"Paris"}'),
        ]
    )

    assert aggregated.message["tool_calls"] == [
        TOOL_CALL,
        {
            "id": "call_2",
            "type": "function",
            "function": {"name": "get_time", "arguments": '{"city": "Lyon"}'},
        },
    ]


def test_aggregate__no_tool_calls__message_keeps_no_tool_keys():
    aggregated = chat_stream_aggregator.aggregate(
        [
            MessageStartV2ChatStreamResponse(type="message-start", id="c9"),
            _content_delta(text="Blue."),
        ]
    )

    assert "tool_calls" not in aggregated.message
    assert "tool_plan" not in aggregated.message


def test_aggregate__empty_tool_plan__kept_as_empty_string():
    # an empty fragment is a value the caller sent, it is not the same as an
    # absent tool plan
    aggregated = chat_stream_aggregator.aggregate(
        [
            MessageStartV2ChatStreamResponse(type="message-start", id="c9"),
            _tool_plan_delta(""),
            _content_delta(text="Blue."),
        ]
    )

    assert aggregated.message["tool_plan"] == ""


def test_to_openai_shaped_usage__missing_tokens__returns_none():
    """A usage payload with only billed_units must not be reported as zero tokens."""
    assert cohere_chat_decorator._to_openai_shaped_usage({"billed_units": {}}) is None
    assert cohere_chat_decorator._to_openai_shaped_usage({}) is None


def test_extract_metadata_from_client__credentials_in_base_url__stripped():
    client = cohere.ClientV2(
        api_key="fake-api-key",
        base_url="https://user:secret@proxy.internal:8443/v2?token=abc#frag",
    )

    metadata = opik_tracker._extract_metadata_from_client(client)

    assert metadata == {"base_url": "https://proxy.internal:8443/v2"}
    assert "secret" not in metadata["base_url"]
    assert "abc" not in metadata["base_url"]
