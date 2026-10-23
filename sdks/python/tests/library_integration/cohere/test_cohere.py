import asyncio
import gc
import json
import weakref

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
from cohere.v2.types.v2chat_stream_response import (
    ContentDeltaV2ChatStreamResponse,
    MessageEndV2ChatStreamResponse,
    MessageStartV2ChatStreamResponse,
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


# assert_all_called=False on purpose: the request is only issued on the first
# iteration, and this test never iterates. The unused route is the point.
@respx.mock(base_url=BASE_URL, assert_all_called=False)
def test_cohere_chat_stream__never_read_then_dropped__span_still_ends(
    respx_mock, fake_backend
):
    """A stream the caller never iterates must still end its span.

    The wrapper is a generator, and a generator's body, including its
    ``finally``, does not run until the first ``next()``. The span is already
    popped from the decorator by then, so without a finalizer it would stay
    open for good.
    """
    respx_mock.post(CHAT_ROUTE).mock(
        return_value=httpx.Response(
            200,
            headers={"Content-Type": "text/event-stream"},
            content=_sse(*_stream_events()),
        )
    )
    client = track_cohere(cohere.ClientV2(api_key="fake-api-key"))

    stream = client.chat_stream(
        model=MODEL, messages=[{"role": "user", "content": "hi"}]
    )
    stream_ref = weakref.ref(stream)
    del stream
    for _ in range(5):
        gc.collect()
        if stream_ref() is None:
            break
    opik.flush_tracker()

    assert len(fake_backend.trace_trees) == 1
    span = fake_backend.trace_trees[0].spans[0]
    assert span.end_time is not None
    assert span.error_info is None
    assert fake_backend.trace_trees[0].end_time is not None


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
