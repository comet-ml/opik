"""Each tracked OpenAI stream is aggregated with its own API's aggregator.

Chat Completions and Responses streams are both `openai.Stream` objects, but
their events are aggregated differently. Creating one kind of stream must not
change how an already-created stream of the other kind is logged.
"""

import json
import logging

import httpx
import openai
import pytest
import pytest_asyncio

import opik
from opik.integrations.openai import track_openai

MODEL = "gpt-4o-mini"
CHAT_WORDS = ["Some ", "chat ", "answer"]
RESPONSES_TEXT = "Some responses answer"


def _sse(events):
    return "".join(
        (f"event: {event['type']}\n" if "type" in event else "")
        + f"data: {json.dumps(event)}\n\n"
        for event in events
    )


def _chat_stream_body():
    chunks = [
        {
            "id": "chatcmpl-1",
            "object": "chat.completion.chunk",
            "created": 1,
            "model": MODEL,
            "choices": [
                {"index": 0, "delta": {"content": word}, "finish_reason": None}
            ],
        }
        for word in CHAT_WORDS
    ]
    return _sse(chunks) + "data: [DONE]\n\n"


def _responses_stream_body():
    response = {
        "id": "resp_1",
        "object": "response",
        "created_at": 1,
        "model": MODEL,
        "status": "completed",
        "output": [
            {
                "id": "msg_1",
                "type": "message",
                "role": "assistant",
                "status": "completed",
                "content": [
                    {"type": "output_text", "text": RESPONSES_TEXT, "annotations": []}
                ],
            }
        ],
        "parallel_tool_calls": True,
        "tool_choice": "auto",
        "tools": [],
    }
    return _sse(
        [
            {
                "type": "response.output_text.delta",
                "item_id": "msg_1",
                "output_index": 0,
                "content_index": 0,
                "delta": RESPONSES_TEXT,
                "sequence_number": 1,
                "logprobs": [],
            },
            {"type": "response.completed", "response": response, "sequence_number": 2},
        ]
    )


def _handler(request):
    body = (
        _responses_stream_body()
        if request.url.path.endswith("/responses")
        else _chat_stream_body()
    )
    return httpx.Response(
        200, headers={"content-type": "text/event-stream"}, content=body.encode()
    )


@pytest.fixture
def tracked_client():
    http_client = httpx.Client(transport=httpx.MockTransport(_handler))
    yield track_openai(openai.OpenAI(api_key="fake-api-key", http_client=http_client))
    http_client.close()


@pytest_asyncio.fixture
async def tracked_async_client():
    http_client = httpx.AsyncClient(transport=httpx.MockTransport(_handler))
    yield track_openai(
        openai.AsyncOpenAI(api_key="fake-api-key", http_client=http_client)
    )
    await http_client.aclose()


def _span(fake_backend, name_prefix):
    return next(
        span
        for trace in fake_backend.trace_trees
        for span in trace.spans
        if span.name.startswith(name_prefix)
    )


def _error_records(caplog):
    return [record for record in caplog.records if record.levelno >= logging.ERROR]


def test_openai_chat_stream__responses_stream_created_before_it_is_read__chat_output_logged(
    fake_backend, tracked_client, caplog
):
    chat_stream = tracked_client.chat.completions.create(
        model=MODEL, messages=[{"role": "user", "content": "hi"}], stream=True
    )
    other_stream = tracked_client.responses.create(model=MODEL, input="hi", stream=True)

    with caplog.at_level(logging.ERROR):
        text = "".join(chunk.choices[0].delta.content or "" for chunk in chat_stream)
    other_stream.close()

    opik.flush_tracker()

    chat_span = _span(fake_backend, "chat_completion")
    assert text == "".join(CHAT_WORDS)
    assert chat_span.output["choices"][0]["message"]["content"] == text
    assert not _error_records(caplog)


def test_openai_responses_stream__chat_stream_created_before_it_is_read__responses_output_logged(
    fake_backend, tracked_client, caplog
):
    responses_stream = tracked_client.responses.create(
        model=MODEL, input="hi", stream=True
    )
    other_stream = tracked_client.chat.completions.create(
        model=MODEL, messages=[{"role": "user", "content": "hi"}], stream=True
    )

    with caplog.at_level(logging.ERROR):
        for _ in responses_stream:
            pass
    other_stream.close()

    opik.flush_tracker()

    responses_span = _span(fake_backend, "responses")
    assert responses_span.output["output"][0]["content"][0]["text"] == RESPONSES_TEXT
    assert not _error_records(caplog)


@pytest.mark.asyncio
async def test_openai_async_chat_stream__responses_stream_created_before_it_is_read__chat_output_logged(
    fake_backend, tracked_async_client, caplog
):
    chat_stream = await tracked_async_client.chat.completions.create(
        model=MODEL, messages=[{"role": "user", "content": "hi"}], stream=True
    )
    other_stream = await tracked_async_client.responses.create(
        model=MODEL, input="hi", stream=True
    )

    text = ""
    with caplog.at_level(logging.ERROR):
        async for chunk in chat_stream:
            text += chunk.choices[0].delta.content or ""
    await other_stream.close()

    opik.flush_tracker()

    chat_span = _span(fake_backend, "chat_completion")
    assert text == "".join(CHAT_WORDS)
    assert chat_span.output["choices"][0]["message"]["content"] == text
    assert not _error_records(caplog)


@pytest.mark.asyncio
async def test_openai_async_responses_stream__chat_stream_created_before_it_is_read__responses_output_logged(
    fake_backend, tracked_async_client, caplog
):
    responses_stream = await tracked_async_client.responses.create(
        model=MODEL, input="hi", stream=True
    )
    other_stream = await tracked_async_client.chat.completions.create(
        model=MODEL, messages=[{"role": "user", "content": "hi"}], stream=True
    )

    with caplog.at_level(logging.ERROR):
        async for _ in responses_stream:
            pass
    await other_stream.close()

    opik.flush_tracker()

    responses_span = _span(fake_backend, "responses")
    assert responses_span.output["output"][0]["content"][0]["text"] == RESPONSES_TEXT
    assert not _error_records(caplog)
