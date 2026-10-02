import contextlib

import cohere
from cohere.types.assistant_message_response import AssistantMessageResponse
from cohere.types.assistant_message_response_content_item import (
    TextAssistantMessageResponseContentItem,
)
from cohere.types.chat_content_delta_event_delta import ChatContentDeltaEventDelta
from cohere.types.chat_content_delta_event_delta_message import (
    ChatContentDeltaEventDeltaMessage,
)
from cohere.types.chat_content_delta_event_delta_message_content import (
    ChatContentDeltaEventDeltaMessageContent,
)
from cohere.types.chat_message_end_event_delta import ChatMessageEndEventDelta
from cohere.types.usage import Usage
from cohere.types.usage_tokens import UsageTokens
from cohere.v2.types.v2chat_response import V2ChatResponse
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

from ...testlib import (
    ANY_BUT_NONE,
    ANY_DICT,
    SpanModel,
    TraceModel,
    assert_equal,
)

MODEL = "command-r-plus"


def _mock_response(
    content: str = "Blue, due to Rayleigh scattering.",
) -> V2ChatResponse:
    return V2ChatResponse(
        id="c1",
        finish_reason="COMPLETE",
        message=AssistantMessageResponse(
            role="assistant",
            content=[
                TextAssistantMessageResponseContentItem(type="text", text=content)
            ],
        ),
        usage=Usage(tokens=UsageTokens(input_tokens=10, output_tokens=8)),
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


def test_cohere_chat__happyflow(fake_backend, monkeypatch):
    client = cohere.ClientV2(api_key="fake-api-key")

    class _Raw:
        def chat(self, **kwargs):
            return type("R", (), {"data": _mock_response()})()

    monkeypatch.setattr(client, "_raw_client", _Raw())
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


def test_cohere_chat__usage_normalized_to_openai_token_names(fake_backend, monkeypatch):
    """Cohere reports tokens.input_tokens / output_tokens, not prompt/completion."""
    client = cohere.ClientV2(api_key="fake-api-key")

    class _Raw:
        def chat(self, **kwargs):
            return type("R", (), {"data": _mock_response()})()

    monkeypatch.setattr(client, "_raw_client", _Raw())
    tracked = track_cohere(client)

    tracked.chat(model=MODEL, messages=[{"role": "user", "content": "hi"}])
    opik.flush_tracker()

    usage = fake_backend.trace_trees[0].spans[0].usage
    assert usage["prompt_tokens"] == 10
    assert usage["completion_tokens"] == 8
    assert usage["total_tokens"] == 18


def test_cohere_chat_stream__aggregates_into_one_span(fake_backend, monkeypatch):
    client = cohere.ClientV2(api_key="fake-api-key")

    events = [
        MessageStartV2ChatStreamResponse(type="message-start", id="c1"),
        _content_delta(text="Blue."),
        MessageEndV2ChatStreamResponse(
            type="message-end",
            id="c1",
            delta=ChatMessageEndEventDelta(
                finish_reason="COMPLETE",
                usage=Usage(tokens=UsageTokens(input_tokens=5, output_tokens=2)),
            ),
        ),
    ]

    class _Raw:
        @contextlib.contextmanager
        def chat_stream(self, **kwargs):
            yield type("R", (), {"data": iter(events)})()

    monkeypatch.setattr(client, "_raw_client", _Raw())
    tracked = track_cohere(client)

    collected = list(
        tracked.chat_stream(model=MODEL, messages=[{"role": "user", "content": "hi"}])
    )
    opik.flush_tracker()

    assert len(collected) == 3
    assert len(fake_backend.trace_trees) == 1
    span = fake_backend.trace_trees[0].spans[0]
    assert span.provider == "cohere"
    assert span.usage["prompt_tokens"] == 5


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
