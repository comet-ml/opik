import asyncio
from typing import Any, Dict, List

import openai
import pytest
from pydantic import BaseModel

import opik
from opik.config import OPIK_PROJECT_DEFAULT_NAME
from opik.integrations.openai import track_openai
from ...testlib import (
    ANY_BUT_NONE,
    ANY_DICT,
    ANY_LIST,
    ANY_STRING,
    SpanModel,
    TraceModel,
    assert_dict_has_keys,
    assert_equal,
)


pytestmark = pytest.mark.usefixtures("ensure_openai_configured")

MODEL_FOR_TESTS = "gpt-4o-mini"
EXPECTED_OPENAI_USAGE_LOGGED_FORMAT = {
    "prompt_tokens": ANY_BUT_NONE,
    "completion_tokens": ANY_BUT_NONE,
    "total_tokens": ANY_BUT_NONE,
    "original_usage.prompt_tokens": ANY_BUT_NONE,
    "original_usage.completion_tokens": ANY_BUT_NONE,
    "original_usage.total_tokens": ANY_BUT_NONE,
    "original_usage.completion_tokens_details.accepted_prediction_tokens": ANY_BUT_NONE,
    "original_usage.completion_tokens_details.audio_tokens": ANY_BUT_NONE,
    "original_usage.completion_tokens_details.reasoning_tokens": ANY_BUT_NONE,
    "original_usage.completion_tokens_details.rejected_prediction_tokens": ANY_BUT_NONE,
    "original_usage.prompt_tokens_details.audio_tokens": ANY_BUT_NONE,
    "original_usage.prompt_tokens_details.cached_tokens": ANY_BUT_NONE,
}


def _assert_metadata_contains_required_keys(metadata: Dict[str, Any]):
    REQUIRED_METADATA_KEYS = [
        "usage",
        "model",
        "max_tokens",
        "created_from",
        "type",
        "id",
        "created",
        "object",
    ]
    assert_dict_has_keys(metadata, REQUIRED_METADATA_KEYS)


@pytest.mark.parametrize(
    "project_name, expected_project_name",
    [
        (None, OPIK_PROJECT_DEFAULT_NAME),
        ("openai-integration-test", "openai-integration-test"),
    ],
)
def test_openai_client_beta_chat_completions_parse__happyflow(
    fake_backend, project_name, expected_project_name
):
    client = openai.OpenAI()
    wrapped_client = track_openai(client, project_name=project_name)

    class CalendarEvent(BaseModel):
        name: str
        date: str
        participants: List[str]

    messages = [
        {"role": "system", "content": "Extract the event information."},
        {
            "role": "user",
            "content": "Alice and Bob are going to a science fair on Friday.",
        },
    ]

    _ = wrapped_client.beta.chat.completions.parse(
        model="gpt-4o",
        messages=messages,
        max_tokens=100,
        response_format=CalendarEvent,
    )

    opik.flush_tracker()

    EXPECTED_TRACE_TREE = TraceModel(
        id=ANY_BUT_NONE,
        name="chat_completion_parse",
        input={"messages": messages},
        output={"choices": ANY_BUT_NONE},
        tags=["openai"],
        metadata=ANY_DICT,
        start_time=ANY_BUT_NONE,
        end_time=ANY_BUT_NONE,
        last_updated_at=ANY_BUT_NONE,
        project_name=expected_project_name,
        spans=[
            SpanModel(
                id=ANY_BUT_NONE,
                type="llm",
                name="chat_completion_parse",
                input={"messages": messages},
                output={"choices": ANY_BUT_NONE},
                tags=["openai"],
                metadata=ANY_DICT,
                usage=EXPECTED_OPENAI_USAGE_LOGGED_FORMAT,
                start_time=ANY_BUT_NONE,
                end_time=ANY_BUT_NONE,
                project_name=expected_project_name,
                spans=[],
                model=ANY_STRING.starting_with("gpt-4o"),
                provider="openai",
            )
        ],
    )

    assert len(fake_backend.trace_trees) == 1
    trace_tree = fake_backend.trace_trees[0]

    assert_equal(EXPECTED_TRACE_TREE, trace_tree)

    llm_span_metadata = trace_tree.spans[0].metadata
    _assert_metadata_contains_required_keys(llm_span_metadata)


def test_async_openai_client_beta_chat_completions_parse__happyflow(fake_backend):
    client = openai.AsyncOpenAI()
    wrapped_client = track_openai(client)

    class CalendarEvent(BaseModel):
        name: str
        date: str
        participants: List[str]

    messages = [
        {"role": "system", "content": "Extract the event information."},
        {
            "role": "user",
            "content": "Alice and Bob are going to a science fair on Friday.",
        },
    ]

    asyncio.run(
        wrapped_client.beta.chat.completions.parse(
            model="gpt-4o",
            messages=messages,
            response_format=CalendarEvent,
            max_tokens=100,
        )
    )

    opik.flush_tracker()

    EXPECTED_TRACE_TREE = TraceModel(
        id=ANY_BUT_NONE,
        name="chat_completion_parse",
        input={"messages": messages},
        output={"choices": ANY_BUT_NONE},
        tags=["openai"],
        metadata=ANY_DICT,
        start_time=ANY_BUT_NONE,
        end_time=ANY_BUT_NONE,
        last_updated_at=ANY_BUT_NONE,
        spans=[
            SpanModel(
                id=ANY_BUT_NONE,
                type="llm",
                name="chat_completion_parse",
                input={"messages": messages},
                output={"choices": ANY_BUT_NONE},
                tags=["openai"],
                metadata=ANY_DICT,
                usage=EXPECTED_OPENAI_USAGE_LOGGED_FORMAT,
                start_time=ANY_BUT_NONE,
                end_time=ANY_BUT_NONE,
                spans=[],
                model=ANY_STRING.starting_with("gpt-4o"),
                provider="openai",
            )
        ],
    )

    assert len(fake_backend.trace_trees) == 1
    trace_tree = fake_backend.trace_trees[0]

    assert_equal(EXPECTED_TRACE_TREE, trace_tree)

    llm_span_metadata = trace_tree.spans[0].metadata
    _assert_metadata_contains_required_keys(llm_span_metadata)


def test_openai_beta_chat_completion_stream__generator_tracked_correctly(
    fake_backend,
):
    client = openai.OpenAI()
    wrapped_client = track_openai(client)
    messages = [
        {
            "role": "system",
            "content": "You are a helpful assistant",
        },
        {
            "role": "user",
            "content": "Tell a short fact",
        },
    ]

    chat_completion_stream_manager = wrapped_client.beta.chat.completions.stream(
        model=MODEL_FOR_TESTS,
        messages=messages,
        max_tokens=10,
        stream_options={"include_usage": True},
    )
    with chat_completion_stream_manager as stream:
        for _ in stream:
            pass

    opik.flush_tracker()

    EXPECTED_TRACE_TREE = TraceModel(
        id=ANY_BUT_NONE,
        name="chat_completion_stream",
        input={"messages": messages},
        output={"choices": ANY_BUT_NONE},
        tags=["openai"],
        metadata=ANY_DICT,
        start_time=ANY_BUT_NONE,
        end_time=ANY_BUT_NONE,
        last_updated_at=ANY_BUT_NONE,
        ttft=ANY_BUT_NONE,
        spans=[
            SpanModel(
                id=ANY_BUT_NONE,
                type="llm",
                name="chat_completion_stream",
                input={"messages": messages},
                output={"choices": ANY_BUT_NONE},
                tags=["openai"],
                metadata=ANY_DICT,
                usage=EXPECTED_OPENAI_USAGE_LOGGED_FORMAT,
                start_time=ANY_BUT_NONE,
                end_time=ANY_BUT_NONE,
                spans=[],
                model=ANY_STRING.starting_with(MODEL_FOR_TESTS),
                provider="openai",
                ttft=ANY_BUT_NONE,
            )
        ],
    )

    assert len(fake_backend.trace_trees) == 1

    assert_equal(EXPECTED_TRACE_TREE, fake_backend.trace_trees[0])

    llm_span_metadata = fake_backend.trace_trees[0].spans[0].metadata
    _assert_metadata_contains_required_keys(llm_span_metadata)


def test_openai_beta_chat_completion_stream__include_usage_is_not_enabled__usage_not_logged(
    fake_backend,
):
    client = openai.OpenAI()
    wrapped_client = track_openai(client)
    messages = [
        {
            "role": "system",
            "content": "You are a helpful assistant",
        },
        {
            "role": "user",
            "content": "Tell a short fact",
        },
    ]

    chat_completion_stream_manager = wrapped_client.beta.chat.completions.stream(
        model=MODEL_FOR_TESTS,
        messages=messages,
        max_tokens=10,
    )
    with chat_completion_stream_manager as stream:
        for _ in stream:
            pass

    opik.flush_tracker()

    EXPECTED_TRACE_TREE = TraceModel(
        id=ANY_BUT_NONE,
        name="chat_completion_stream",
        input={"messages": messages},
        output={"choices": ANY_BUT_NONE},
        tags=["openai"],
        metadata=ANY_DICT,
        start_time=ANY_BUT_NONE,
        end_time=ANY_BUT_NONE,
        last_updated_at=ANY_BUT_NONE,
        ttft=ANY_BUT_NONE,
        spans=[
            SpanModel(
                id=ANY_BUT_NONE,
                type="llm",
                name="chat_completion_stream",
                input={"messages": messages},
                output={"choices": ANY_BUT_NONE},
                tags=["openai"],
                metadata=ANY_DICT,
                usage=None,
                start_time=ANY_BUT_NONE,
                end_time=ANY_BUT_NONE,
                spans=[],
                model=ANY_STRING.starting_with(MODEL_FOR_TESTS),
                provider="openai",
                ttft=ANY_BUT_NONE,
            )
        ],
    )

    assert len(fake_backend.trace_trees) == 1

    assert_equal(EXPECTED_TRACE_TREE, fake_backend.trace_trees[0])

    llm_span_metadata = fake_backend.trace_trees[0].spans[0].metadata
    _assert_metadata_contains_required_keys(llm_span_metadata)


def test_openai_beta_chat_completion_stream__stream_called_2_times__generator_tracked_correctly(
    fake_backend,
):
    def run_stream(messages):
        chat_completion_stream_manager = wrapped_client.beta.chat.completions.stream(
            model=MODEL_FOR_TESTS,
            messages=messages,
            max_tokens=10,
            stream_options={"include_usage": True},
        )
        with chat_completion_stream_manager as stream:
            for _ in stream:
                pass

    client = openai.OpenAI()
    wrapped_client = track_openai(client)

    SHORT_FACT_MESSAGES = [
        {
            "role": "user",
            "content": "Tell a short fact",
        }
    ]
    JOKE_MESSAGES = [
        {
            "role": "user",
            "content": "Tell a short joke",
        }
    ]
    run_stream(messages=SHORT_FACT_MESSAGES)
    run_stream(messages=JOKE_MESSAGES)

    opik.flush_tracker()

    EXPECTED_TRACE_TREE_WITH_SHORT_FACT = TraceModel(
        id=ANY_BUT_NONE,
        name="chat_completion_stream",
        input={"messages": SHORT_FACT_MESSAGES},
        output={"choices": ANY_BUT_NONE},
        tags=["openai"],
        metadata=ANY_DICT,
        start_time=ANY_BUT_NONE,
        end_time=ANY_BUT_NONE,
        last_updated_at=ANY_BUT_NONE,
        ttft=ANY_BUT_NONE,
        spans=[
            SpanModel(
                id=ANY_BUT_NONE,
                type="llm",
                name="chat_completion_stream",
                input={"messages": SHORT_FACT_MESSAGES},
                output={"choices": ANY_BUT_NONE},
                tags=["openai"],
                metadata=ANY_DICT,
                usage=EXPECTED_OPENAI_USAGE_LOGGED_FORMAT,
                start_time=ANY_BUT_NONE,
                end_time=ANY_BUT_NONE,
                spans=[],
                model=ANY_STRING.starting_with(MODEL_FOR_TESTS),
                provider="openai",
                ttft=ANY_BUT_NONE,
            )
        ],
    )
    EXPECTED_TRACE_TREE_WITH_JOKE = TraceModel(
        id=ANY_BUT_NONE,
        name="chat_completion_stream",
        input={"messages": JOKE_MESSAGES},
        output={"choices": ANY_BUT_NONE},
        tags=["openai"],
        metadata=ANY_DICT,
        start_time=ANY_BUT_NONE,
        end_time=ANY_BUT_NONE,
        last_updated_at=ANY_BUT_NONE,
        ttft=ANY_BUT_NONE,
        spans=[
            SpanModel(
                id=ANY_BUT_NONE,
                type="llm",
                name="chat_completion_stream",
                input={"messages": JOKE_MESSAGES},
                output={"choices": ANY_BUT_NONE},
                tags=["openai"],
                metadata=ANY_DICT,
                usage=EXPECTED_OPENAI_USAGE_LOGGED_FORMAT,
                start_time=ANY_BUT_NONE,
                end_time=ANY_BUT_NONE,
                spans=[],
                model=ANY_STRING.starting_with(MODEL_FOR_TESTS),
                provider="openai",
                ttft=ANY_BUT_NONE,
            )
        ],
    )

    assert len(fake_backend.trace_trees) == 2

    assert_equal(EXPECTED_TRACE_TREE_WITH_SHORT_FACT, fake_backend.trace_trees[0])
    assert_equal(EXPECTED_TRACE_TREE_WITH_JOKE, fake_backend.trace_trees[1])

    llm_fact_span_metadata = fake_backend.trace_trees[0].spans[0].metadata
    _assert_metadata_contains_required_keys(llm_fact_span_metadata)

    llm_joke_span_metadata = fake_backend.trace_trees[1].spans[0].metadata
    _assert_metadata_contains_required_keys(llm_joke_span_metadata)


def test_openai_beta_chat_completion_stream__get_final_completion_called__generator_tracked_correctly(
    fake_backend,
):
    client = openai.OpenAI()
    wrapped_client = track_openai(client)
    messages = [
        {
            "role": "system",
            "content": "You are a helpful assistant",
        },
        {
            "role": "user",
            "content": "Tell a short fact",
        },
    ]

    chat_completion_stream_manager = wrapped_client.beta.chat.completions.stream(
        model=MODEL_FOR_TESTS,
        messages=messages,
        max_tokens=200,  # increased max tokens because get_final_completion() fails on low ones
        stream_options={"include_usage": True},
    )
    with chat_completion_stream_manager as stream:
        stream.get_final_completion()

    opik.flush_tracker()

    EXPECTED_TRACE_TREE = TraceModel(
        id=ANY_BUT_NONE,
        name="chat_completion_stream",
        input={"messages": messages},
        output={"choices": ANY_LIST},
        tags=["openai"],
        metadata=ANY_DICT,
        start_time=ANY_BUT_NONE,
        end_time=ANY_BUT_NONE,
        last_updated_at=ANY_BUT_NONE,
        ttft=ANY_BUT_NONE,
        spans=[
            SpanModel(
                id=ANY_BUT_NONE,
                name="chat_completion_stream",
                input={"messages": messages},
                output={"choices": ANY_LIST},
                tags=["openai"],
                metadata=ANY_DICT,
                start_time=ANY_BUT_NONE,
                end_time=ANY_BUT_NONE,
                type="llm",
                usage=EXPECTED_OPENAI_USAGE_LOGGED_FORMAT,
                spans=[],
                model=ANY_STRING.starting_with(MODEL_FOR_TESTS),
                provider="openai",
                ttft=ANY_BUT_NONE,
            )
        ],
    )

    assert len(fake_backend.trace_trees) == 1

    assert_equal(EXPECTED_TRACE_TREE, fake_backend.trace_trees[0])
    llm_span_metadata = fake_backend.trace_trees[0].spans[0].metadata
    _assert_metadata_contains_required_keys(llm_span_metadata)


def test_openai_beta_chat_completion_stream__get_final_completion_called_after_stream_iteration_loop__generator_tracked_correctly_only_once(
    fake_backend,
):
    client = openai.OpenAI()
    wrapped_client = track_openai(client)
    messages = [
        {
            "role": "system",
            "content": "You are a helpful assistant",
        },
        {
            "role": "user",
            "content": "Tell a short fact",
        },
    ]

    chat_completion_stream_manager = wrapped_client.beta.chat.completions.stream(
        model=MODEL_FOR_TESTS,
        messages=messages,
        max_tokens=200,  # increased max tokens because get_final_completion() fails on low ones
        stream_options={"include_usage": True},
    )
    with chat_completion_stream_manager as stream:
        for _ in stream:
            pass
        stream.get_final_completion()

    opik.flush_tracker()

    EXPECTED_TRACE_TREE = TraceModel(
        id=ANY_BUT_NONE,
        name="chat_completion_stream",
        input={"messages": messages},
        output={"choices": ANY_LIST},
        tags=["openai"],
        metadata=ANY_DICT,
        start_time=ANY_BUT_NONE,
        end_time=ANY_BUT_NONE,
        last_updated_at=ANY_BUT_NONE,
        ttft=ANY_BUT_NONE,
        spans=[
            SpanModel(
                id=ANY_BUT_NONE,
                name="chat_completion_stream",
                input={"messages": messages},
                output={"choices": ANY_LIST},
                tags=["openai"],
                metadata=ANY_DICT,
                start_time=ANY_BUT_NONE,
                end_time=ANY_BUT_NONE,
                type="llm",
                usage=EXPECTED_OPENAI_USAGE_LOGGED_FORMAT,
                spans=[],
                model=ANY_STRING.starting_with(MODEL_FOR_TESTS),
                provider="openai",
                ttft=ANY_BUT_NONE,
            )
        ],
    )

    assert len(fake_backend.trace_trees) == 1

    assert_equal(EXPECTED_TRACE_TREE, fake_backend.trace_trees[0])
    llm_span_metadata = fake_backend.trace_trees[0].spans[0].metadata
    _assert_metadata_contains_required_keys(llm_span_metadata)


def test_async_openai_beta_chat_completion_stream__data_tracked_correctly(
    fake_backend,
):
    client = openai.AsyncOpenAI()
    wrapped_client = track_openai(client)
    messages = [
        {
            "role": "system",
            "content": "You are a helpful assistant",
        },
        {
            "role": "user",
            "content": "Tell a short fact",
        },
    ]

    async def async_f():
        chat_completion_stream_manager = wrapped_client.beta.chat.completions.stream(
            model=MODEL_FOR_TESTS,
            messages=messages,
            max_tokens=10,
            stream_options={"include_usage": True},
        )
        async with chat_completion_stream_manager as stream:
            async for _ in stream:
                pass

    asyncio.run(async_f())

    opik.flush_tracker()

    EXPECTED_TRACE_TREE = TraceModel(
        id=ANY_BUT_NONE,
        name="chat_completion_stream",
        input={"messages": messages},
        output={"choices": ANY_LIST},
        tags=["openai"],
        metadata=ANY_DICT,
        start_time=ANY_BUT_NONE,
        end_time=ANY_BUT_NONE,
        last_updated_at=ANY_BUT_NONE,
        ttft=ANY_BUT_NONE,
        spans=[
            SpanModel(
                id=ANY_BUT_NONE,
                name="chat_completion_stream",
                input={"messages": messages},
                output={"choices": ANY_LIST},
                tags=["openai"],
                metadata=ANY_DICT,
                start_time=ANY_BUT_NONE,
                end_time=ANY_BUT_NONE,
                type="llm",
                usage=EXPECTED_OPENAI_USAGE_LOGGED_FORMAT,
                spans=[],
                model=ANY_STRING.starting_with(MODEL_FOR_TESTS),
                provider="openai",
                ttft=ANY_BUT_NONE,
            )
        ],
    )

    assert len(fake_backend.trace_trees) == 1

    assert_equal(EXPECTED_TRACE_TREE, fake_backend.trace_trees[0])
    llm_span_metadata = fake_backend.trace_trees[0].spans[0].metadata
    _assert_metadata_contains_required_keys(llm_span_metadata)


def test_async_openai_beta_chat_completion_stream__get_final_completion_called_twice__data_tracked_correctly_once(
    fake_backend,
):
    client = openai.AsyncOpenAI()
    wrapped_client = track_openai(client)
    messages = [
        {
            "role": "system",
            "content": "You are a helpful assistant",
        },
        {
            "role": "user",
            "content": "Tell a short fact",
        },
    ]

    async def async_f():
        chat_completion_stream_manager = wrapped_client.beta.chat.completions.stream(
            model=MODEL_FOR_TESTS,
            messages=messages,
            max_tokens=200,  # increased max tokens because get_final_completion() fails on low ones
            stream_options={"include_usage": True},
        )
        async with chat_completion_stream_manager as stream:
            await stream.get_final_completion()
            await stream.get_final_completion()

    asyncio.run(async_f())

    opik.flush_tracker()

    EXPECTED_TRACE_TREE = TraceModel(
        id=ANY_BUT_NONE,
        name="chat_completion_stream",
        input={"messages": messages},
        output={"choices": ANY_LIST},
        tags=["openai"],
        metadata=ANY_DICT,
        start_time=ANY_BUT_NONE,
        end_time=ANY_BUT_NONE,
        last_updated_at=ANY_BUT_NONE,
        ttft=ANY_BUT_NONE,
        spans=[
            SpanModel(
                id=ANY_BUT_NONE,
                name="chat_completion_stream",
                input={"messages": messages},
                output={"choices": ANY_LIST},
                tags=["openai"],
                metadata=ANY_DICT,
                start_time=ANY_BUT_NONE,
                end_time=ANY_BUT_NONE,
                type="llm",
                usage=EXPECTED_OPENAI_USAGE_LOGGED_FORMAT,
                spans=[],
                model=ANY_STRING.starting_with(MODEL_FOR_TESTS),
                provider="openai",
                ttft=ANY_BUT_NONE,
            )
        ],
    )

    assert len(fake_backend.trace_trees) == 1

    assert_equal(EXPECTED_TRACE_TREE, fake_backend.trace_trees[0])
    llm_span_metadata = fake_backend.trace_trees[0].spans[0].metadata
    _assert_metadata_contains_required_keys(llm_span_metadata)
