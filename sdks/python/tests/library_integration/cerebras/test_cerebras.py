import asyncio
import json
from types import SimpleNamespace

import cerebras.cloud.sdk as cerebras
import httpx
import pytest
import respx
from cerebras.cloud.sdk.types.chat.chat_completion import (
    ChatChunkResponse,
    ChatCompletionResponse,
    ChatCompletionResponseChoice,
    ChatCompletionResponseChoiceMessage,
    ChatCompletionResponseTimeInfo,
    ChatCompletionResponseUsage,
)

import opik
from opik.config import OPIK_PROJECT_DEFAULT_NAME
from opik.integrations.cerebras import chat_completion_chunks_aggregator, track_cerebras

from ...testlib import (
    ANY_BUT_NONE,
    ANY_DICT,
    SpanModel,
    TraceModel,
    assert_equal,
)

MODEL = "llama-3.3-70b"
BASE_URL = "https://api.cerebras.ai"
COMPLETIONS_ROUTE = "/v1/chat/completions"


def _mock_completion(
    content: str = "Blue, due to Rayleigh scattering.",
) -> ChatCompletionResponse:
    return ChatCompletionResponse(
        id="c1",
        object="chat.completion",
        created=0,
        model=MODEL,
        system_fingerprint="fp_test",
        time_info=ChatCompletionResponseTimeInfo(),
        choices=[
            ChatCompletionResponseChoice(
                index=0,
                finish_reason="stop",
                message=ChatCompletionResponseChoiceMessage(
                    role="assistant", content=content
                ),
            )
        ],
        usage=ChatCompletionResponseUsage(
            prompt_tokens=10, completion_tokens=8, total_tokens=18
        ),
    )


@pytest.mark.parametrize(
    "project_name, expected_project_name",
    [
        (None, OPIK_PROJECT_DEFAULT_NAME),
        ("cerebras-integration-test", "cerebras-integration-test"),
    ],
)
def test_cerebras_chat_completions_create__happyflow(
    fake_backend, monkeypatch, project_name, expected_project_name
):
    client = cerebras.Cerebras(api_key="fake-api-key")
    wrapped_client = track_cerebras(client, project_name=project_name)
    monkeypatch.setattr(
        client.chat.completions, "_post", lambda *args, **kwargs: _mock_completion()
    )

    messages = [{"role": "user", "content": "Why is the sky blue?"}]
    response = wrapped_client.chat.completions.create(
        model=MODEL,
        messages=messages,
        max_tokens=10,
    )

    opik.flush_tracker()

    assert response.choices[0].message.content == "Blue, due to Rayleigh scattering."

    expected_output = {"choices": response.model_dump(mode="json")["choices"]}

    EXPECTED_TRACE_TREE = TraceModel(
        id=ANY_BUT_NONE,
        name="chat_completion_create",
        input={"messages": messages},
        output=expected_output,
        tags=["cerebras"],
        metadata=ANY_DICT,
        start_time=ANY_BUT_NONE,
        end_time=ANY_BUT_NONE,
        last_updated_at=ANY_BUT_NONE,
        project_name=expected_project_name,
        spans=[
            SpanModel(
                id=ANY_BUT_NONE,
                name="chat_completion_create",
                input={"messages": messages},
                output=expected_output,
                tags=["cerebras"],
                metadata=ANY_DICT,
                start_time=ANY_BUT_NONE,
                end_time=ANY_BUT_NONE,
                project_name=expected_project_name,
                type="llm",
                usage=ANY_DICT,
                model=MODEL,
                provider="cerebras",
                spans=[],
                source="sdk",
            )
        ],
        source="sdk",
    )

    assert len(fake_backend.trace_trees) == 1
    assert_equal(EXPECTED_TRACE_TREE, fake_backend.trace_trees[0])


def test_cerebras_chat_completions_create__async__happyflow(fake_backend, monkeypatch):
    client = cerebras.AsyncCerebras(api_key="fake-api-key")
    wrapped_client = track_cerebras(client)

    async def _mock_post(*args, **kwargs):
        return _mock_completion()

    monkeypatch.setattr(client.chat.completions, "_post", _mock_post)

    messages = [{"role": "user", "content": "Why is the sky blue?"}]

    async def run() -> None:
        await wrapped_client.chat.completions.create(model=MODEL, messages=messages)

    asyncio.run(run())
    opik.flush_tracker()

    assert len(fake_backend.trace_trees) == 1
    span = fake_backend.trace_trees[0].spans[0]
    assert span.provider == "cerebras"
    assert span.type == "llm"
    assert span.model == MODEL


def test_cerebras_chat_completions_create__error__span_and_trace_finished_gracefully(
    fake_backend, monkeypatch
):
    client = cerebras.Cerebras(api_key="fake-api-key")
    wrapped_client = track_cerebras(client)

    def _raise(*args, **kwargs):
        raise Exception("network is down")

    monkeypatch.setattr(client.chat.completions, "_post", _raise)

    with pytest.raises(Exception):
        wrapped_client.chat.completions.create(model=MODEL, messages=None)

    opik.flush_tracker()

    assert len(fake_backend.trace_trees) == 1
    trace_tree = fake_backend.trace_trees[0]
    assert trace_tree.spans[0].error_info is not None
    assert trace_tree.spans[0].provider == "cerebras"


def _stream_chunk(delta, finish_reason=None):
    return ChatChunkResponse.model_validate(
        {
            "id": "c1",
            "object": "chat.completion.chunk",
            "created": 0,
            "model": MODEL,
            "system_fingerprint": "fp_test",
            "choices": [{"index": 0, "delta": delta, "finish_reason": finish_reason}],
        }
    )


def test_aggregate__streamed_tool_call__kept_in_message():
    aggregated = chat_completion_chunks_aggregator.aggregate(
        [
            _stream_chunk({"role": "assistant"}),
            _stream_chunk(
                {
                    "tool_calls": [
                        {
                            "index": 0,
                            "id": "call_1",
                            "type": "function",
                            "function": {
                                "name": "get_weather",
                                "arguments": '{"city": ',
                            },
                        }
                    ]
                }
            ),
            _stream_chunk(
                {
                    "tool_calls": [
                        {
                            "index": 0,
                            "type": "function",
                            "function": {"arguments": '"Paris"}'},
                        }
                    ]
                }
            ),
            _stream_chunk({}, finish_reason="tool_calls"),
        ]
    )

    assert aggregated.choices[0] == {
        "index": 0,
        "finish_reason": "tool_calls",
        "message": {
            "role": "assistant",
            "content": "",
            "tool_calls": [
                {
                    "id": "call_1",
                    "type": "function",
                    "function": {
                        "name": "get_weather",
                        "arguments": '{"city": "Paris"}',
                    },
                }
            ],
        },
    }


def _sse(*chunks: dict) -> bytes:
    """Encode chunks as the ``text/event-stream`` body Cerebras sends."""
    body = "".join(f"data: {json.dumps(c)}\n\n" for c in chunks)
    return (body + "data: [DONE]\n\n").encode()


@respx.mock(base_url=BASE_URL)
def test_cerebras_chat_completions_create__stream_with_tool_call__tool_call_in_span_output(
    respx_mock, fake_backend
):
    """A streamed tool call goes through the real SSE parser and into the span output."""

    def chunk(delta: dict, finish_reason=None) -> dict:
        return {
            "id": "c1",
            "object": "chat.completion.chunk",
            "created": 0,
            "model": MODEL,
            "system_fingerprint": "fp_test",
            "choices": [{"index": 0, "delta": delta, "finish_reason": finish_reason}],
        }

    respx_mock.post(COMPLETIONS_ROUTE).mock(
        return_value=httpx.Response(
            200,
            headers={"Content-Type": "text/event-stream"},
            content=_sse(
                chunk({"role": "assistant"}),
                chunk(
                    {
                        "tool_calls": [
                            {
                                "index": 0,
                                "id": "call_1",
                                "type": "function",
                                "function": {
                                    "name": "get_weather",
                                    "arguments": '{"city": ',
                                },
                            }
                        ]
                    }
                ),
                chunk(
                    {
                        "tool_calls": [
                            {
                                "index": 0,
                                "type": "function",
                                "function": {"arguments": '"Paris"}'},
                            }
                        ]
                    }
                ),
                chunk({}, finish_reason="tool_calls"),
            ),
        )
    )
    client = cerebras.Cerebras(api_key="fake-api-key")
    tracked = track_cerebras(client)

    stream = tracked.chat.completions.create(
        model=MODEL,
        messages=[{"role": "user", "content": "Weather in Paris?"}],
        stream=True,
    )
    list(stream)
    opik.flush_tracker()

    assert len(fake_backend.trace_trees) == 1
    span = fake_backend.trace_trees[0].spans[0]
    assert span.provider == "cerebras"
    assert span.output["choices"][0] == {
        "index": 0,
        "finish_reason": "tool_calls",
        "message": {
            "role": "assistant",
            "content": "",
            "tool_calls": [
                {
                    "id": "call_1",
                    "type": "function",
                    "function": {
                        "name": "get_weather",
                        "arguments": '{"city": "Paris"}',
                    },
                }
            ],
        },
    }


def test_aggregate__delta_without_tool_calls_attribute__content_kept():
    """Older SDK versions give the delta no `tool_calls` field at all."""
    chunk = SimpleNamespace(
        id="c1",
        created=0,
        model=MODEL,
        system_fingerprint="fp_test",
        usage=None,
        choices=[
            SimpleNamespace(
                delta=SimpleNamespace(role="assistant", content="Blue."),
                finish_reason="stop",
            )
        ],
    )

    aggregated = chat_completion_chunks_aggregator.aggregate([chunk])

    assert aggregated is not None
    assert aggregated.choices[0] == {
        "index": 0,
        "finish_reason": "stop",
        "message": {"role": "assistant", "content": "Blue."},
    }


def test_aggregate__reasoning_deltas__kept_alongside_content():
    aggregated = chat_completion_chunks_aggregator.aggregate(
        [
            _stream_chunk({"role": "assistant"}),
            _stream_chunk({"reasoning": "Short wavelengths "}),
            _stream_chunk({"reasoning": "scatter more."}),
            _stream_chunk({"content": "Blue."}),
            _stream_chunk({}, finish_reason="stop"),
        ]
    )

    message = aggregated.choices[0]["message"]
    assert message["content"] == "Blue."
    assert message["reasoning"] == "Short wavelengths scatter more."
    assert "tool_calls" not in message
