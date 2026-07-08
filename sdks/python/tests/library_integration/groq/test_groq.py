import asyncio
import json
from typing import Any, Dict, List

import groq
import httpx
import pytest
from groq.types.chat.chat_completion import ChatCompletion, Choice
from groq.types.chat.chat_completion_message import ChatCompletionMessage
from groq.types.completion_usage import CompletionUsage

import opik
from opik.config import OPIK_PROJECT_DEFAULT_NAME
from opik.integrations.groq import track_groq

from ...testlib import (
    ANY_BUT_NONE,
    ANY_DICT,
    ANY_LIST,
    ANY_STRING,
    SpanModel,
    TraceModel,
    assert_equal,
)

MODEL = "llama-3.3-70b-versatile"

STREAM_CHUNKS = [
    {
        "id": "chatcmpl-1",
        "object": "chat.completion.chunk",
        "created": 0,
        "model": MODEL,
        "choices": [
            {
                "index": 0,
                "delta": {"role": "assistant", "content": ""},
                "finish_reason": None,
            }
        ],
    },
    {
        "id": "chatcmpl-1",
        "object": "chat.completion.chunk",
        "created": 0,
        "model": MODEL,
        "choices": [
            {
                "index": 0,
                "delta": {"content": "Blue, due to Rayleigh scattering."},
                "finish_reason": None,
            }
        ],
    },
    {
        "id": "chatcmpl-1",
        "object": "chat.completion.chunk",
        "created": 0,
        "model": MODEL,
        "choices": [{"index": 0, "delta": {}, "finish_reason": "stop"}],
        # Groq places usage stats on the terminal chunk under x_groq.usage.
        "x_groq": {
            "usage": {"prompt_tokens": 10, "completion_tokens": 8, "total_tokens": 18}
        },
    },
]

STREAM_EXPECTED_USAGE = {
    "prompt_tokens": 10,
    "completion_tokens": 8,
    "total_tokens": 18,
}

STREAM_EXPECTED_OUTPUT = {
    "choices": [
        {
            "index": 0,
            "message": {
                "role": "assistant",
                "content": "Blue, due to Rayleigh scattering.",
            },
            "finish_reason": "stop",
        }
    ]
}


def _mock_stream_response(chunks: List[Dict[str, Any]]) -> httpx.Response:
    body = "".join(f"data: {json.dumps(chunk)}\n\n" for chunk in chunks)
    body += "data: [DONE]\n\n"
    return httpx.Response(
        200,
        content=body.encode("utf-8"),
        request=httpx.Request(
            "POST", "https://api.groq.com/openai/v1/chat/completions"
        ),
    )


def _mock_stream_transport(chunks: List[Dict[str, Any]]) -> httpx.MockTransport:
    """Public testing hook: a fake transport plugged in via the client's
    ``http_client`` argument, so streaming exercises the real request/response
    machinery (auth headers, ``_post``, ``Stream``/``AsyncStream`` construction)
    instead of monkeypatching a private method.
    """

    def handler(request: httpx.Request) -> httpx.Response:
        return _mock_stream_response(chunks)

    return httpx.MockTransport(handler)


def _mock_completion(
    content: str = "Blue, due to Rayleigh scattering.",
) -> ChatCompletion:
    return ChatCompletion(
        id="cmpl-1",
        object="chat.completion",
        created=0,
        model=MODEL,
        choices=[
            Choice(
                index=0,
                finish_reason="stop",
                logprobs=None,
                message=ChatCompletionMessage(role="assistant", content=content),
            )
        ],
        usage=CompletionUsage(prompt_tokens=10, completion_tokens=8, total_tokens=18),
    )


@pytest.mark.parametrize(
    "project_name, expected_project_name",
    [
        (None, OPIK_PROJECT_DEFAULT_NAME),
        ("groq-integration-test", "groq-integration-test"),
    ],
)
def test_groq_chat_completions_create__happyflow(
    fake_backend, monkeypatch, project_name, expected_project_name
):
    client = groq.Groq(api_key="fake-api-key")
    wrapped_client = track_groq(client, project_name=project_name)
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
        tags=["groq"],
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
                tags=["groq"],
                metadata=ANY_DICT,
                start_time=ANY_BUT_NONE,
                end_time=ANY_BUT_NONE,
                project_name=expected_project_name,
                type="llm",
                usage=ANY_DICT,
                model=MODEL,
                provider="groq",
                spans=[],
                source="sdk",
            )
        ],
        source="sdk",
    )

    assert len(fake_backend.trace_trees) == 1
    assert_equal(EXPECTED_TRACE_TREE, fake_backend.trace_trees[0])


def test_groq_chat_completions_create__async__happyflow(fake_backend, monkeypatch):
    client = groq.AsyncGroq(api_key="fake-api-key")
    wrapped_client = track_groq(client)

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
    assert span.provider == "groq"
    assert span.type == "llm"
    assert span.model == MODEL


def test_groq_chat_completions_create__stream_mode_is_on__generator_tracked_correctly(
    fake_backend,
):
    http_client = httpx.Client(transport=_mock_stream_transport(STREAM_CHUNKS))
    client = groq.Groq(api_key="fake-api-key", http_client=http_client)
    wrapped_client = track_groq(client)

    messages = [{"role": "user", "content": "Why is the sky blue?"}]
    stream = wrapped_client.chat.completions.create(
        model=MODEL, messages=messages, stream=True
    )
    for _ in stream:
        pass

    opik.flush_tracker()

    EXPECTED_TRACE_TREE = TraceModel(
        id=ANY_BUT_NONE,
        name="chat_completion_stream",
        input={"messages": messages},
        output=STREAM_EXPECTED_OUTPUT,
        tags=["groq"],
        metadata=ANY_DICT,
        start_time=ANY_BUT_NONE,
        end_time=ANY_BUT_NONE,
        last_updated_at=ANY_BUT_NONE,
        project_name=OPIK_PROJECT_DEFAULT_NAME,
        spans=[
            SpanModel(
                id=ANY_BUT_NONE,
                name="chat_completion_stream",
                input={"messages": messages},
                output=STREAM_EXPECTED_OUTPUT,
                tags=["groq"],
                metadata=ANY_DICT,
                start_time=ANY_BUT_NONE,
                end_time=ANY_BUT_NONE,
                project_name=OPIK_PROJECT_DEFAULT_NAME,
                type="llm",
                usage=ANY_DICT.containing(STREAM_EXPECTED_USAGE),
                model=MODEL,
                provider="groq",
                spans=[],
                source="sdk",
            )
        ],
        source="sdk",
    )

    assert len(fake_backend.trace_trees) == 1
    assert_equal(EXPECTED_TRACE_TREE, fake_backend.trace_trees[0])


def test_groq_chat_completions_create__async__stream_mode_is_on__generator_tracked_correctly(
    fake_backend,
):
    http_client = httpx.AsyncClient(transport=_mock_stream_transport(STREAM_CHUNKS))
    client = groq.AsyncGroq(api_key="fake-api-key", http_client=http_client)
    wrapped_client = track_groq(client)

    messages = [{"role": "user", "content": "Why is the sky blue?"}]

    async def run() -> None:
        stream = await wrapped_client.chat.completions.create(
            model=MODEL, messages=messages, stream=True
        )
        async for _ in stream:
            pass

    asyncio.run(run())
    opik.flush_tracker()

    EXPECTED_TRACE_TREE = TraceModel(
        id=ANY_BUT_NONE,
        name="chat_completion_stream",
        input={"messages": messages},
        output=STREAM_EXPECTED_OUTPUT,
        tags=["groq"],
        metadata=ANY_DICT,
        start_time=ANY_BUT_NONE,
        end_time=ANY_BUT_NONE,
        last_updated_at=ANY_BUT_NONE,
        project_name=OPIK_PROJECT_DEFAULT_NAME,
        spans=[
            SpanModel(
                id=ANY_BUT_NONE,
                name="chat_completion_stream",
                input={"messages": messages},
                output=STREAM_EXPECTED_OUTPUT,
                tags=["groq"],
                metadata=ANY_DICT,
                start_time=ANY_BUT_NONE,
                end_time=ANY_BUT_NONE,
                project_name=OPIK_PROJECT_DEFAULT_NAME,
                type="llm",
                usage=ANY_DICT.containing(STREAM_EXPECTED_USAGE),
                model=MODEL,
                provider="groq",
                spans=[],
                source="sdk",
            )
        ],
        source="sdk",
    )

    assert len(fake_backend.trace_trees) == 1
    assert_equal(EXPECTED_TRACE_TREE, fake_backend.trace_trees[0])


@pytest.mark.usefixtures("ensure_groq_configured")
def test_groq_chat_completions_create__stream_mode_is_on__e2e__generator_tracked_correctly(
    fake_backend,
):
    """Real Groq API call (requires GROQ_API_KEY) validating the streaming
    path — including the x_groq.usage fallback — against the actual API
    response shape, not just our hand-built SSE fixtures.
    """
    client = groq.Groq()
    wrapped_client = track_groq(client)

    messages = [{"role": "user", "content": "Say the word 'hello' and nothing else."}]
    stream = wrapped_client.chat.completions.create(
        model=MODEL,
        messages=messages,
        stream=True,
    )
    for _ in stream:
        pass

    opik.flush_tracker()

    assert len(fake_backend.trace_trees) == 1

    EXPECTED_TRACE_TREE = TraceModel(
        id=ANY_BUT_NONE,
        name="chat_completion_stream",
        input={"messages": messages},
        output=ANY_DICT.containing({"choices": ANY_LIST}),
        tags=["groq"],
        metadata=ANY_DICT,
        start_time=ANY_BUT_NONE,
        end_time=ANY_BUT_NONE,
        last_updated_at=ANY_BUT_NONE,
        project_name=OPIK_PROJECT_DEFAULT_NAME,
        spans=[
            SpanModel(
                id=ANY_BUT_NONE,
                name="chat_completion_stream",
                input={"messages": messages},
                output=ANY_DICT.containing({"choices": ANY_LIST}),
                tags=["groq"],
                metadata=ANY_DICT,
                start_time=ANY_BUT_NONE,
                end_time=ANY_BUT_NONE,
                project_name=OPIK_PROJECT_DEFAULT_NAME,
                type="llm",
                usage=ANY_DICT.containing(
                    {
                        "prompt_tokens": ANY_BUT_NONE,
                        "completion_tokens": ANY_BUT_NONE,
                        "total_tokens": ANY_BUT_NONE,
                    }
                ),
                model=ANY_STRING.starting_with(MODEL),
                provider="groq",
                spans=[],
                source="sdk",
            )
        ],
        source="sdk",
    )

    assert_equal(EXPECTED_TRACE_TREE, fake_backend.trace_trees[0])


@pytest.mark.usefixtures("ensure_groq_configured")
def test_groq_chat_completions_create__async__stream_mode_is_on__e2e__generator_tracked_correctly(
    fake_backend,
):
    """Async counterpart of the real-API streaming test above."""
    client = groq.AsyncGroq()
    wrapped_client = track_groq(client)

    messages = [{"role": "user", "content": "Say the word 'hello' and nothing else."}]

    async def run() -> None:
        stream = await wrapped_client.chat.completions.create(
            model=MODEL,
            messages=messages,
            stream=True,
        )
        async for _ in stream:
            pass

    asyncio.run(run())
    opik.flush_tracker()

    assert len(fake_backend.trace_trees) == 1

    EXPECTED_TRACE_TREE = TraceModel(
        id=ANY_BUT_NONE,
        name="chat_completion_stream",
        input={"messages": messages},
        output=ANY_DICT.containing({"choices": ANY_LIST}),
        tags=["groq"],
        metadata=ANY_DICT,
        start_time=ANY_BUT_NONE,
        end_time=ANY_BUT_NONE,
        last_updated_at=ANY_BUT_NONE,
        project_name=OPIK_PROJECT_DEFAULT_NAME,
        spans=[
            SpanModel(
                id=ANY_BUT_NONE,
                name="chat_completion_stream",
                input={"messages": messages},
                output=ANY_DICT.containing({"choices": ANY_LIST}),
                tags=["groq"],
                metadata=ANY_DICT,
                start_time=ANY_BUT_NONE,
                end_time=ANY_BUT_NONE,
                project_name=OPIK_PROJECT_DEFAULT_NAME,
                type="llm",
                usage=ANY_DICT.containing(
                    {
                        "prompt_tokens": ANY_BUT_NONE,
                        "completion_tokens": ANY_BUT_NONE,
                        "total_tokens": ANY_BUT_NONE,
                    }
                ),
                model=ANY_STRING.starting_with(MODEL),
                provider="groq",
                spans=[],
                source="sdk",
            )
        ],
        source="sdk",
    )

    assert_equal(EXPECTED_TRACE_TREE, fake_backend.trace_trees[0])


def test_groq_chat_completions_create__error__span_and_trace_finished_gracefully(
    fake_backend, monkeypatch
):
    client = groq.Groq(api_key="fake-api-key")
    wrapped_client = track_groq(client)

    def _raise(*args, **kwargs):
        raise Exception("network is down")

    monkeypatch.setattr(client.chat.completions, "_post", _raise)

    with pytest.raises(Exception):
        wrapped_client.chat.completions.create(model=MODEL, messages=None)

    opik.flush_tracker()

    assert len(fake_backend.trace_trees) == 1
    trace_tree = fake_backend.trace_trees[0]
    assert trace_tree.spans[0].error_info is not None
    assert trace_tree.spans[0].provider == "groq"
