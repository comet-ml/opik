import json

import httpx
import pytest

import opik
from mistralai import Mistral
from opik.integrations.mistral import track_mistral

MODEL = "magistral-medium-latest"
ANSWER = "Paris."
REASONING = "The user asks for the capital of France."


def _chunk(delta, finish_reason=None, usage=None):
    event = {
        "id": "b3f1d1f0e8d94f8f9d4b8d7e5a1c2b3d",
        "object": "chat.completion.chunk",
        "created": 1758000000,
        "model": MODEL,
        "choices": [
            {
                "index": 0,
                "delta": delta,
                "finish_reason": finish_reason,
                "logprobs": None,
            }
        ],
    }
    if usage:
        event["usage"] = usage
    return "data: " + json.dumps(event) + "\n\n"


def _stream_body():
    # A reasoning stream: a text chunk list (every 1.x SDK accepts it), then a
    # string delta, then the usage. The order must survive into the span.
    return (
        _chunk({"role": "assistant", "content": ""})
        + _chunk({"content": [{"type": "text", "text": REASONING}]})
        + _chunk({"content": " " + ANSWER})
        + _chunk(
            {"content": ""},
            finish_reason="stop",
            usage={"prompt_tokens": 20, "completion_tokens": 41, "total_tokens": 61},
        )
        + "data: [DONE]\n\n"
    )


def _handler(request):
    return httpx.Response(
        200, text=_stream_body(), headers={"content-type": "text/event-stream"}
    )


def _assert_span_output(fake_backend):
    assert len(fake_backend.trace_trees) == 1
    llm_span = fake_backend.trace_trees[0].spans[0]
    assert llm_span.output is not None, "the streamed span lost its output"
    content = llm_span.output["choices"][0]["message"]["content"]
    assert content == [
        {"type": "text", "text": REASONING},
        {"type": "text", "text": " " + ANSWER},
    ]
    assert llm_span.output["choices"][0]["finish_reason"] == "stop"
    assert llm_span.usage["completion_tokens"] == 41


def test_mistral_chat_stream__chunk_list_deltas__span_output_kept_in_order(
    fake_backend,
):
    with httpx.Client(transport=httpx.MockTransport(_handler)) as http_client:
        wrapped_client = track_mistral(
            Mistral(api_key="fake-api-key", client=http_client)
        )
        events = list(
            wrapped_client.chat.stream(
                model=MODEL,
                messages=[{"role": "user", "content": "Capital of France?"}],
            )
        )
    assert len(events) == 4

    opik.flush_tracker()
    _assert_span_output(fake_backend)


@pytest.mark.asyncio
async def test_mistral_chat_stream_async__chunk_list_deltas__span_output_kept_in_order(
    fake_backend,
):
    async with httpx.AsyncClient(
        transport=httpx.MockTransport(_handler)
    ) as http_client:
        wrapped_client = track_mistral(
            Mistral(api_key="fake-api-key", async_client=http_client)
        )
        stream = await wrapped_client.chat.stream_async(
            model=MODEL, messages=[{"role": "user", "content": "Capital of France?"}]
        )
        events = [event async for event in stream]
    assert len(events) == 4

    opik.flush_tracker()
    _assert_span_output(fake_backend)
