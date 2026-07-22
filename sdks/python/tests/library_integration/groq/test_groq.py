import asyncio

import groq
import pytest

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


@pytest.mark.usefixtures("ensure_groq_configured")
def test_groq_chat_completions_create__e2e__generator_tracked_correctly(
    fake_backend,
):
    """Real Groq API call (requires GROQ_API_KEY) validating the
    non-streaming path against the actual API response shape.
    """
    client = groq.Groq()
    wrapped_client = track_groq(client)

    messages = [{"role": "user", "content": "Say the word 'hello' and nothing else."}]
    wrapped_client.chat.completions.create(
        model=MODEL,
        messages=messages,
        max_tokens=10,
    )

    opik.flush_tracker()

    assert len(fake_backend.trace_trees) == 1

    EXPECTED_TRACE_TREE = TraceModel(
        id=ANY_BUT_NONE,
        name="chat_completion_create",
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
                name="chat_completion_create",
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
def test_groq_chat_completions_create__async__e2e__generator_tracked_correctly(
    fake_backend,
):
    """Async counterpart of the real-API non-streaming test above."""
    client = groq.AsyncGroq()
    wrapped_client = track_groq(client)

    messages = [{"role": "user", "content": "Say the word 'hello' and nothing else."}]

    async def run() -> None:
        await wrapped_client.chat.completions.create(
            model=MODEL,
            messages=messages,
            max_tokens=10,
        )

    asyncio.run(run())
    opik.flush_tracker()

    assert len(fake_backend.trace_trees) == 1

    EXPECTED_TRACE_TREE = TraceModel(
        id=ANY_BUT_NONE,
        name="chat_completion_create",
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
                name="chat_completion_create",
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
