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
from opik import semantic_version

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
OPENAI_OLDER_THAN_1_92_0 = (
    semantic_version.SemanticVersion.parse(openai.__version__) < "1.92.0"
)


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
def test_openai_client_chat_completions_create__happyflow(
    fake_backend, project_name, expected_project_name
):
    client = openai.OpenAI()
    wrapped_client = track_openai(
        openai_client=client,
        project_name=project_name,
    )
    messages = [
        {"role": "system", "content": "You are a helpful assistant."},
        {"role": "user", "content": "Tell a fact"},
    ]

    _ = wrapped_client.chat.completions.create(
        model=MODEL_FOR_TESTS,
        messages=messages,
        max_tokens=10,
    )

    opik.flush_tracker()

    EXPECTED_TRACE_TREE = TraceModel(
        id=ANY_BUT_NONE,
        name="chat_completion_create",
        input=ANY_DICT.containing({"messages": messages}),
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
                name="chat_completion_create",
                input=ANY_DICT.containing({"messages": messages}),
                output={"choices": ANY_BUT_NONE},
                tags=["openai"],
                metadata=ANY_DICT,
                usage=EXPECTED_OPENAI_USAGE_LOGGED_FORMAT,
                start_time=ANY_BUT_NONE,
                end_time=ANY_BUT_NONE,
                project_name=expected_project_name,
                spans=[],
                model=ANY_STRING.starting_with(MODEL_FOR_TESTS),
                provider="openai",
            )
        ],
    )

    assert len(fake_backend.trace_trees) == 1
    trace_tree = fake_backend.trace_trees[0]

    assert_equal(EXPECTED_TRACE_TREE, trace_tree)

    llm_span_metadata = trace_tree.spans[0].metadata
    _assert_metadata_contains_required_keys(llm_span_metadata)


def test_openai_client_chat_completions_create__create_raises_an_error__span_and_trace_finished_gracefully__error_info_is_logged(
    fake_backend,
):
    client = openai.OpenAI()
    wrapped_client = track_openai(client)

    with pytest.raises(openai.OpenAIError):
        _ = wrapped_client.chat.completions.create(
            messages=None,
            model=None,
        )

    opik.flush_tracker()

    EXPECTED_TRACE_TREE = TraceModel(
        id=ANY_BUT_NONE,
        name="chat_completion_create",
        input=ANY_DICT.containing({"messages": None}),
        output=None,
        tags=["openai"],
        metadata={
            "created_from": "openai",
            "type": "openai_chat",
            "model": None,
        },
        start_time=ANY_BUT_NONE,
        end_time=ANY_BUT_NONE,
        last_updated_at=ANY_BUT_NONE,
        project_name=ANY_BUT_NONE,
        error_info={
            "exception_type": ANY_STRING,
            "message": ANY_STRING,
            "traceback": ANY_STRING,
        },
        spans=[
            SpanModel(
                id=ANY_BUT_NONE,
                type="llm",
                name="chat_completion_create",
                input=ANY_DICT.containing({"messages": None}),
                output=None,
                tags=["openai"],
                metadata={
                    "created_from": "openai",
                    "type": "openai_chat",
                    "model": None,
                },
                usage=None,
                start_time=ANY_BUT_NONE,
                end_time=ANY_BUT_NONE,
                project_name=ANY_BUT_NONE,
                model=None,
                provider="openai",
                error_info={
                    "exception_type": ANY_STRING,
                    "message": ANY_STRING,
                    "traceback": ANY_STRING,
                },
                spans=[],
            )
        ],
    )

    assert len(fake_backend.trace_trees) == 1

    trace_tree = fake_backend.trace_trees[0]
    assert_equal(EXPECTED_TRACE_TREE, trace_tree)


def test_openai_client_chat_completions_create__openai_call_made_in_another_tracked_function__openai_span_attached_to_existing_trace(
    fake_backend,
):
    project_name = "openai-integration-test"

    messages = [
        {"role": "system", "content": "You are a helpful assistant."},
        {"role": "user", "content": "Tell a fact"},
    ]

    @opik.track(project_name=project_name)
    def f():
        client = openai.OpenAI()
        wrapped_client = track_openai(
            openai_client=client,
            # we are trying to log span into another project, but parent's project name will be used
            project_name="openai-integration-test-nested-level",
        )

        _ = wrapped_client.chat.completions.create(
            model=MODEL_FOR_TESTS,
            messages=messages,
            max_tokens=10,
        )

    f()

    opik.flush_tracker()

    EXPECTED_TRACE_TREE = TraceModel(
        id=ANY_BUT_NONE,
        name="f",
        input={},
        output=None,
        start_time=ANY_BUT_NONE,
        end_time=ANY_BUT_NONE,
        last_updated_at=ANY_BUT_NONE,
        project_name=project_name,
        spans=[
            SpanModel(
                id=ANY_BUT_NONE,
                name="f",
                input={},
                output=None,
                start_time=ANY_BUT_NONE,
                end_time=ANY_BUT_NONE,
                project_name=project_name,
                model=None,
                provider=None,
                spans=[
                    SpanModel(
                        id=ANY_BUT_NONE,
                        type="llm",
                        name="chat_completion_create",
                        input=ANY_DICT.containing({"messages": messages}),
                        output={"choices": ANY_BUT_NONE},
                        tags=["openai"],
                        metadata=ANY_DICT,
                        usage=EXPECTED_OPENAI_USAGE_LOGGED_FORMAT,
                        start_time=ANY_BUT_NONE,
                        end_time=ANY_BUT_NONE,
                        project_name=project_name,
                        spans=[],
                        model=ANY_STRING.starting_with(MODEL_FOR_TESTS),
                        provider="openai",
                    )
                ],
            )
        ],
    )

    assert len(fake_backend.trace_trees) == 1

    trace_tree = fake_backend.trace_trees[0]

    assert_equal(EXPECTED_TRACE_TREE, trace_tree)

    llm_span_metadata = trace_tree.spans[0].spans[0].metadata
    _assert_metadata_contains_required_keys(llm_span_metadata)


def test_openai_client_chat_completions_create__async_openai_call_made_in_another_tracked_async_function__openai_span_attached_to_existing_trace(
    fake_backend,
):
    messages = [
        {"role": "system", "content": "You are a helpful assistant."},
        {"role": "user", "content": "Tell a fact"},
    ]

    @opik.track()
    async def async_f():
        client = openai.AsyncOpenAI()
        wrapped_client = track_openai(client)
        _ = await wrapped_client.chat.completions.create(
            model=MODEL_FOR_TESTS,
            messages=messages,
            max_tokens=10,
        )

    asyncio.run(async_f())

    opik.flush_tracker()

    EXPECTED_TRACE_TREE = TraceModel(
        id=ANY_BUT_NONE,
        name="async_f",
        input={},
        output=None,
        start_time=ANY_BUT_NONE,
        end_time=ANY_BUT_NONE,
        last_updated_at=ANY_BUT_NONE,
        project_name=ANY_BUT_NONE,
        spans=[
            SpanModel(
                id=ANY_BUT_NONE,
                name="async_f",
                input={},
                output=None,
                start_time=ANY_BUT_NONE,
                end_time=ANY_BUT_NONE,
                project_name=ANY_BUT_NONE,
                model=None,
                provider=None,
                spans=[
                    SpanModel(
                        id=ANY_BUT_NONE,
                        type="llm",
                        name="chat_completion_create",
                        input=ANY_DICT.containing({"messages": messages}),
                        output={"choices": ANY_BUT_NONE},
                        tags=["openai"],
                        metadata=ANY_DICT,
                        usage=EXPECTED_OPENAI_USAGE_LOGGED_FORMAT,
                        start_time=ANY_BUT_NONE,
                        end_time=ANY_BUT_NONE,
                        project_name=ANY_BUT_NONE,
                        spans=[],
                        model=ANY_STRING.starting_with(MODEL_FOR_TESTS),
                        provider="openai",
                    )
                ],
            )
        ],
    )

    assert len(fake_backend.trace_trees) == 1
    trace_tree = fake_backend.trace_trees[0]

    assert_equal(EXPECTED_TRACE_TREE, trace_tree)

    llm_span_metadata = trace_tree.spans[0].spans[0].metadata
    _assert_metadata_contains_required_keys(llm_span_metadata)


def test_openai_client_chat_completions_create__stream_mode_is_on__generator_tracked_correctly(
    fake_backend,
):
    client = openai.OpenAI()
    wrapped_client = track_openai(client)
    messages = [
        {"role": "system", "content": "You are a helpful assistant."},
        {"role": "user", "content": "Tell a fact"},
    ]

    stream = wrapped_client.chat.completions.create(
        model=MODEL_FOR_TESTS,
        messages=messages,
        max_tokens=10,
        stream=True,
        stream_options={"include_usage": True},
    )

    for item in stream:
        pass

    opik.flush_tracker()

    EXPECTED_TRACE_TREE = TraceModel(
        id=ANY_BUT_NONE,
        name="chat_completion_stream",
        input=ANY_DICT.containing({"messages": messages}),
        output={"choices": ANY_BUT_NONE},
        tags=["openai"],
        metadata=ANY_DICT,
        start_time=ANY_BUT_NONE,
        end_time=ANY_BUT_NONE,
        last_updated_at=ANY_BUT_NONE,
        project_name=ANY_BUT_NONE,
        spans=[
            SpanModel(
                id=ANY_BUT_NONE,
                type="llm",
                name="chat_completion_stream",
                input=ANY_DICT.containing({"messages": messages}),
                output={"choices": ANY_BUT_NONE},
                tags=["openai"],
                metadata=ANY_DICT,
                usage=EXPECTED_OPENAI_USAGE_LOGGED_FORMAT,
                start_time=ANY_BUT_NONE,
                end_time=ANY_BUT_NONE,
                project_name=ANY_BUT_NONE,
                spans=[],
                model=ANY_STRING.starting_with(MODEL_FOR_TESTS),
                provider="openai",
            )
        ],
    )

    assert len(fake_backend.trace_trees) == 1
    trace_tree = fake_backend.trace_trees[0]

    assert_equal(EXPECTED_TRACE_TREE, trace_tree)

    llm_span_metadata = trace_tree.spans[0].metadata
    _assert_metadata_contains_required_keys(llm_span_metadata)


def test_openai_client_chat_completions_create__async_openai_call_made_in_another_tracked_async_function__streaming_mode_enabled__openai_span_attached_to_existing_trace(
    fake_backend,
):
    messages = [
        {"role": "system", "content": "You are a helpful assistant."},
        {"role": "user", "content": "Tell a fact"},
    ]

    @opik.track()
    async def async_f():
        client = openai.AsyncOpenAI()
        wrapped_client = track_openai(client)
        stream = await wrapped_client.chat.completions.create(
            model=MODEL_FOR_TESTS,
            messages=messages,
            max_tokens=10,
            stream=True,
            stream_options={"include_usage": True},
        )
        async for item in stream:
            pass

    asyncio.run(async_f())

    opik.flush_tracker()

    EXPECTED_TRACE_TREE = TraceModel(
        id=ANY_BUT_NONE,
        name="async_f",
        input={},
        output=None,
        start_time=ANY_BUT_NONE,
        end_time=ANY_BUT_NONE,
        last_updated_at=ANY_BUT_NONE,
        project_name=ANY_BUT_NONE,
        spans=[
            SpanModel(
                id=ANY_BUT_NONE,
                name="async_f",
                input={},
                output=None,
                start_time=ANY_BUT_NONE,
                end_time=ANY_BUT_NONE,
                project_name=ANY_BUT_NONE,
                model=None,
                provider=None,
                spans=[
                    SpanModel(
                        id=ANY_BUT_NONE,
                        type="llm",
                        name="chat_completion_stream",
                        input=ANY_DICT.containing({"messages": messages}),
                        output={"choices": ANY_BUT_NONE},
                        tags=["openai"],
                        metadata=ANY_DICT,
                        usage=EXPECTED_OPENAI_USAGE_LOGGED_FORMAT,
                        start_time=ANY_BUT_NONE,
                        end_time=ANY_BUT_NONE,
                        project_name=ANY_BUT_NONE,
                        spans=[],
                        model=ANY_STRING.starting_with(MODEL_FOR_TESTS),
                        provider="openai",
                    )
                ],
            )
        ],
    )

    assert len(fake_backend.trace_trees) == 1
    trace_tree = fake_backend.trace_trees[0]

    assert_equal(EXPECTED_TRACE_TREE, trace_tree)

    llm_span_metadata = trace_tree.spans[0].spans[0].metadata
    _assert_metadata_contains_required_keys(llm_span_metadata)


@pytest.mark.skipif(OPENAI_OLDER_THAN_1_92_0, reason="OpenAI version is too old")
@pytest.mark.parametrize(
    "project_name, expected_project_name",
    [
        (None, OPIK_PROJECT_DEFAULT_NAME),
        ("openai-integration-test", "openai-integration-test"),
    ],
)
def test_openai_client_chat_completions_parse__happyflow(
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

    _ = wrapped_client.chat.completions.parse(
        model="gpt-4o",
        messages=messages,
        max_tokens=100,
        response_format=CalendarEvent,
    )

    opik.flush_tracker()

    EXPECTED_TRACE_TREE = TraceModel(
        id=ANY_BUT_NONE,
        name="chat_completion_parse",
        input=ANY_DICT.containing({"messages": messages}),
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
                input=ANY_DICT.containing({"messages": messages}),
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


@pytest.mark.skipif(OPENAI_OLDER_THAN_1_92_0, reason="OpenAI version is too old")
def test_async_openai_client_chat_completions_parse__happyflow(fake_backend):
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
        wrapped_client.chat.completions.parse(
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
        input=ANY_DICT.containing({"messages": messages}),
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
                input=ANY_DICT.containing({"messages": messages}),
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


@pytest.mark.skipif(OPENAI_OLDER_THAN_1_92_0, reason="OpenAI version is too old")
def test_openai_chat_completion_stream__generator_tracked_correctly(
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

    chat_completion_stream_manager = wrapped_client.chat.completions.stream(
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
        input=ANY_DICT.containing({"messages": messages}),
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
                name="chat_completion_stream",
                input=ANY_DICT.containing({"messages": messages}),
                output={"choices": ANY_BUT_NONE},
                tags=["openai"],
                metadata=ANY_DICT,
                usage=EXPECTED_OPENAI_USAGE_LOGGED_FORMAT,
                start_time=ANY_BUT_NONE,
                end_time=ANY_BUT_NONE,
                spans=[],
                model=ANY_STRING.starting_with(MODEL_FOR_TESTS),
                provider="openai",
            )
        ],
    )

    assert len(fake_backend.trace_trees) == 1

    assert_equal(EXPECTED_TRACE_TREE, fake_backend.trace_trees[0])

    llm_span_metadata = fake_backend.trace_trees[0].spans[0].metadata
    _assert_metadata_contains_required_keys(llm_span_metadata)


@pytest.mark.skipif(OPENAI_OLDER_THAN_1_92_0, reason="OpenAI version is too old")
def test_openai_chat_completion_stream__include_usage_is_not_enabled__usage_not_logged(
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

    chat_completion_stream_manager = wrapped_client.chat.completions.stream(
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
        input=ANY_DICT.containing({"messages": messages}),
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
                name="chat_completion_stream",
                input=ANY_DICT.containing({"messages": messages}),
                output={"choices": ANY_BUT_NONE},
                tags=["openai"],
                metadata=ANY_DICT,
                usage=None,
                start_time=ANY_BUT_NONE,
                end_time=ANY_BUT_NONE,
                spans=[],
                model=ANY_STRING.starting_with(MODEL_FOR_TESTS),
                provider="openai",
            )
        ],
    )

    assert len(fake_backend.trace_trees) == 1

    assert_equal(EXPECTED_TRACE_TREE, fake_backend.trace_trees[0])

    llm_span_metadata = fake_backend.trace_trees[0].spans[0].metadata
    _assert_metadata_contains_required_keys(llm_span_metadata)


@pytest.mark.skipif(OPENAI_OLDER_THAN_1_92_0, reason="OpenAI version is too old")
def test_openai_chat_completion_stream__stream_called_2_times__generator_tracked_correctly(
    fake_backend,
):
    def run_stream(messages):
        chat_completion_stream_manager = wrapped_client.chat.completions.stream(
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
        input=ANY_DICT.containing({"messages": SHORT_FACT_MESSAGES}),
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
                name="chat_completion_stream",
                input=ANY_DICT.containing({"messages": SHORT_FACT_MESSAGES}),
                output={"choices": ANY_BUT_NONE},
                tags=["openai"],
                metadata=ANY_DICT,
                usage=EXPECTED_OPENAI_USAGE_LOGGED_FORMAT,
                start_time=ANY_BUT_NONE,
                end_time=ANY_BUT_NONE,
                spans=[],
                model=ANY_STRING.starting_with(MODEL_FOR_TESTS),
                provider="openai",
            )
        ],
    )
    EXPECTED_TRACE_TREE_WITH_JOKE = TraceModel(
        id=ANY_BUT_NONE,
        name="chat_completion_stream",
        input=ANY_DICT.containing({"messages": JOKE_MESSAGES}),
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
                name="chat_completion_stream",
                input=ANY_DICT.containing({"messages": JOKE_MESSAGES}),
                output={"choices": ANY_BUT_NONE},
                tags=["openai"],
                metadata=ANY_DICT,
                usage=EXPECTED_OPENAI_USAGE_LOGGED_FORMAT,
                start_time=ANY_BUT_NONE,
                end_time=ANY_BUT_NONE,
                spans=[],
                model=ANY_STRING.starting_with(MODEL_FOR_TESTS),
                provider="openai",
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


@pytest.mark.skipif(OPENAI_OLDER_THAN_1_92_0, reason="OpenAI version is too old")
def test_openai_chat_completion_stream__get_final_completion_called__generator_tracked_correctly(
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

    chat_completion_stream_manager = wrapped_client.chat.completions.stream(
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
        input=ANY_DICT.containing({"messages": messages}),
        output={"choices": ANY_LIST},
        tags=["openai"],
        metadata=ANY_DICT,
        start_time=ANY_BUT_NONE,
        end_time=ANY_BUT_NONE,
        last_updated_at=ANY_BUT_NONE,
        spans=[
            SpanModel(
                id=ANY_BUT_NONE,
                name="chat_completion_stream",
                input=ANY_DICT.containing({"messages": messages}),
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
            )
        ],
    )

    assert len(fake_backend.trace_trees) == 1

    assert_equal(EXPECTED_TRACE_TREE, fake_backend.trace_trees[0])
    llm_span_metadata = fake_backend.trace_trees[0].spans[0].metadata
    _assert_metadata_contains_required_keys(llm_span_metadata)


@pytest.mark.skipif(OPENAI_OLDER_THAN_1_92_0, reason="OpenAI version is too old")
def test_openai_chat_completion_stream__get_final_completion_called_after_stream_iteration_loop__generator_tracked_correctly_only_once(
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

    chat_completion_stream_manager = wrapped_client.chat.completions.stream(
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
        input=ANY_DICT.containing({"messages": messages}),
        output={"choices": ANY_LIST},
        tags=["openai"],
        metadata=ANY_DICT,
        start_time=ANY_BUT_NONE,
        end_time=ANY_BUT_NONE,
        last_updated_at=ANY_BUT_NONE,
        spans=[
            SpanModel(
                id=ANY_BUT_NONE,
                name="chat_completion_stream",
                input=ANY_DICT.containing({"messages": messages}),
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
            )
        ],
    )

    assert len(fake_backend.trace_trees) == 1

    assert_equal(EXPECTED_TRACE_TREE, fake_backend.trace_trees[0])
    llm_span_metadata = fake_backend.trace_trees[0].spans[0].metadata
    _assert_metadata_contains_required_keys(llm_span_metadata)


@pytest.mark.skipif(OPENAI_OLDER_THAN_1_92_0, reason="OpenAI version is too old")
def test_async_openai_chat_completion_stream__data_tracked_correctly(
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
        chat_completion_stream_manager = wrapped_client.chat.completions.stream(
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
        input=ANY_DICT.containing({"messages": messages}),
        output={"choices": ANY_LIST},
        tags=["openai"],
        metadata=ANY_DICT,
        start_time=ANY_BUT_NONE,
        end_time=ANY_BUT_NONE,
        last_updated_at=ANY_BUT_NONE,
        spans=[
            SpanModel(
                id=ANY_BUT_NONE,
                name="chat_completion_stream",
                input=ANY_DICT.containing({"messages": messages}),
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
            )
        ],
    )

    assert len(fake_backend.trace_trees) == 1

    assert_equal(EXPECTED_TRACE_TREE, fake_backend.trace_trees[0])
    llm_span_metadata = fake_backend.trace_trees[0].spans[0].metadata
    _assert_metadata_contains_required_keys(llm_span_metadata)


@pytest.mark.skipif(OPENAI_OLDER_THAN_1_92_0, reason="OpenAI version is too old")
def test_async_openai_chat_completion_stream__get_final_completion_called_twice__data_tracked_correctly_once(
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

    # async def async_f0():
    #     chat_completion_stream_manager = wrapped_client.chat.completions.stream(
    #         model=MODEL_FOR_TESTS,
    #         messages=messages,
    #         max_tokens=10,
    #         stream_options={"include_usage": True},
    #     )
    #     async with chat_completion_stream_manager as stream:
    #         async for _ in stream:
    #             pass

    # asyncio.run(async_f0())

    async def async_f():
        chat_completion_stream_manager = wrapped_client.chat.completions.stream(
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
        input=ANY_DICT.containing({"messages": messages}),
        output={"choices": ANY_LIST},
        tags=["openai"],
        metadata=ANY_DICT,
        start_time=ANY_BUT_NONE,
        end_time=ANY_BUT_NONE,
        last_updated_at=ANY_BUT_NONE,
        spans=[
            SpanModel(
                id=ANY_BUT_NONE,
                name="chat_completion_stream",
                input=ANY_DICT.containing({"messages": messages}),
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
            )
        ],
    )

    assert len(fake_backend.trace_trees) == 1

    assert_equal(EXPECTED_TRACE_TREE, fake_backend.trace_trees[0])
    llm_span_metadata = fake_backend.trace_trees[0].spans[0].metadata
    _assert_metadata_contains_required_keys(llm_span_metadata)


@pytest.mark.parametrize(
    "project_name, expected_project_name",
    [
        (None, OPIK_PROJECT_DEFAULT_NAME),
        ("openai-integration-test", "openai-integration-test"),
    ],
)
def test_openai_client_chat_completions_create__opik_args__happyflow(
    fake_backend, project_name, expected_project_name
):
    # test that opik_args are passed to the logged traces and spans
    client = openai.OpenAI()
    wrapped_client = track_openai(
        openai_client=client,
        project_name=project_name,
    )
    messages = [
        {"role": "system", "content": "You are a helpful assistant."},
        {"role": "user", "content": "Tell a fact"},
    ]

    args_dict = {
        "span": {"tags": ["span_tag"], "metadata": {"span_key": "span_value"}},
        "trace": {
            "thread_id": "conversation-2",
            "tags": ["trace_tag"],
            "metadata": {"trace_key": "trace_value"},
        },
    }

    _ = wrapped_client.chat.completions.create(
        model=MODEL_FOR_TESTS, messages=messages, max_tokens=10, opik_args=args_dict
    )

    opik.flush_tracker()

    EXPECTED_TRACE_TREE = TraceModel(
        id=ANY_BUT_NONE,
        name="chat_completion_create",
        input=ANY_DICT.containing({"messages": messages}),
        output={"choices": ANY_BUT_NONE},
        tags=["openai", "span_tag", "trace_tag"],
        metadata=ANY_DICT.containing({"trace_key": "trace_value"}),
        start_time=ANY_BUT_NONE,
        end_time=ANY_BUT_NONE,
        last_updated_at=ANY_BUT_NONE,
        project_name=expected_project_name,
        thread_id="conversation-2",
        spans=[
            SpanModel(
                id=ANY_BUT_NONE,
                type="llm",
                name="chat_completion_create",
                input=ANY_DICT.containing({"messages": messages}),
                output={"choices": ANY_BUT_NONE},
                tags=["openai", "span_tag"],
                metadata=ANY_DICT.containing({"span_key": "span_value"}),
                usage=EXPECTED_OPENAI_USAGE_LOGGED_FORMAT,
                start_time=ANY_BUT_NONE,
                end_time=ANY_BUT_NONE,
                project_name=expected_project_name,
                spans=[],
                model=ANY_STRING.starting_with(MODEL_FOR_TESTS),
                provider="openai",
            )
        ],
    )

    assert len(fake_backend.trace_trees) == 1
    trace_tree = fake_backend.trace_trees[0]

    assert_equal(EXPECTED_TRACE_TREE, trace_tree)

    llm_span_metadata = trace_tree.spans[0].metadata
    _assert_metadata_contains_required_keys(llm_span_metadata)


# Maximum reasonable time-to-first-token in milliseconds for test assertions
MAX_REASONABLE_TTFT_MS = 60000


def test_openai_client_chat_completions_create__stream_mode__ttft_tracked_in_span(
    fake_backend,
):
    """Test that time-to-first-token is tracked and stored in LLM span's ttft field for streaming."""
    client = openai.OpenAI()
    wrapped_client = track_openai(client)
    messages = [
        {"role": "system", "content": "You are a helpful assistant."},
        {"role": "user", "content": "Tell a short fact"},
    ]

    stream = wrapped_client.chat.completions.create(
        model=MODEL_FOR_TESTS,
        messages=messages,
        max_tokens=10,
        stream=True,
        stream_options={"include_usage": True},
    )

    for _ in stream:
        pass

    opik.flush_tracker()

    assert len(fake_backend.trace_trees) == 1
    trace_tree = fake_backend.trace_trees[0]

    llm_spans = [span for span in trace_tree.spans if span.type == "llm"]
    assert len(llm_spans) == 1, "Expected exactly one LLM span"

    llm_span = llm_spans[0]
    assert llm_span.ttft is not None, "LLM span should have ttft field set for streaming"
    assert isinstance(llm_span.ttft, (int, float)), (
        f"ttft should be a number, got {type(llm_span.ttft)}"
    )
    assert llm_span.ttft >= 0, f"ttft should be non-negative, got {llm_span.ttft}"
    assert llm_span.ttft < MAX_REASONABLE_TTFT_MS, (
        f"ttft should be reasonable (< {MAX_REASONABLE_TTFT_MS}ms), got {llm_span.ttft}"
    )


def test_openai_client_chat_completions_create__async_stream_mode__ttft_tracked_in_span(
    fake_backend,
):
    """Test that time-to-first-token is tracked for async streaming."""
    messages = [
        {"role": "system", "content": "You are a helpful assistant."},
        {"role": "user", "content": "Tell a short fact"},
    ]

    async def async_stream():
        client = openai.AsyncOpenAI()
        wrapped_client = track_openai(client)
        stream = await wrapped_client.chat.completions.create(
            model=MODEL_FOR_TESTS,
            messages=messages,
            max_tokens=10,
            stream=True,
            stream_options={"include_usage": True},
        )
        async for _ in stream:
            pass

    asyncio.run(async_stream())
    opik.flush_tracker()

    assert len(fake_backend.trace_trees) == 1
    trace_tree = fake_backend.trace_trees[0]

    llm_spans = [span for span in trace_tree.spans if span.type == "llm"]
    assert len(llm_spans) == 1, "Expected exactly one LLM span"

    llm_span = llm_spans[0]
    assert llm_span.ttft is not None, (
        "LLM span should have ttft field set for async streaming"
    )
    assert isinstance(llm_span.ttft, (int, float)), (
        f"ttft should be a number, got {type(llm_span.ttft)}"
    )
    assert llm_span.ttft >= 0, f"ttft should be non-negative, got {llm_span.ttft}"
    assert llm_span.ttft < MAX_REASONABLE_TTFT_MS, (
        f"ttft should be reasonable (< {MAX_REASONABLE_TTFT_MS}ms), got {llm_span.ttft}"
    )


@pytest.mark.skipif(OPENAI_OLDER_THAN_1_92_0, reason="OpenAI version is too old")
def test_openai_chat_completion_stream__context_manager__ttft_tracked_in_span(
    fake_backend,
):
    """Test that time-to-first-token is tracked for stream context manager."""
    client = openai.OpenAI()
    wrapped_client = track_openai(client)
    messages = [
        {"role": "system", "content": "You are a helpful assistant"},
        {"role": "user", "content": "Tell a short fact"},
    ]

    chat_completion_stream_manager = wrapped_client.chat.completions.stream(
        model=MODEL_FOR_TESTS,
        messages=messages,
        max_tokens=10,
        stream_options={"include_usage": True},
    )
    with chat_completion_stream_manager as stream:
        for _ in stream:
            pass

    opik.flush_tracker()

    assert len(fake_backend.trace_trees) == 1
    trace_tree = fake_backend.trace_trees[0]

    llm_spans = [span for span in trace_tree.spans if span.type == "llm"]
    assert len(llm_spans) == 1, "Expected exactly one LLM span"

    llm_span = llm_spans[0]
    assert llm_span.ttft is not None, (
        "LLM span should have ttft field set for stream context manager"
    )
    assert isinstance(llm_span.ttft, (int, float)), (
        f"ttft should be a number, got {type(llm_span.ttft)}"
    )
    assert llm_span.ttft >= 0, f"ttft should be non-negative, got {llm_span.ttft}"
    assert llm_span.ttft < MAX_REASONABLE_TTFT_MS, (
        f"ttft should be reasonable (< {MAX_REASONABLE_TTFT_MS}ms), got {llm_span.ttft}"
    )


@pytest.mark.skipif(OPENAI_OLDER_THAN_1_92_0, reason="OpenAI version is too old")
def test_openai_chat_completion_stream__async_context_manager__ttft_tracked_in_span(
    fake_backend,
):
    """Test that time-to-first-token is tracked for async stream context manager."""
    client = openai.AsyncOpenAI()
    wrapped_client = track_openai(client)
    messages = [
        {"role": "system", "content": "You are a helpful assistant"},
        {"role": "user", "content": "Tell a short fact"},
    ]

    async def async_stream():
        chat_completion_stream_manager = wrapped_client.chat.completions.stream(
            model=MODEL_FOR_TESTS,
            messages=messages,
            max_tokens=10,
            stream_options={"include_usage": True},
        )
        async with chat_completion_stream_manager as stream:
            async for _ in stream:
                pass

    asyncio.run(async_stream())
    opik.flush_tracker()

    assert len(fake_backend.trace_trees) == 1
    trace_tree = fake_backend.trace_trees[0]

    llm_spans = [span for span in trace_tree.spans if span.type == "llm"]
    assert len(llm_spans) == 1, "Expected exactly one LLM span"

    llm_span = llm_spans[0]
    assert llm_span.ttft is not None, (
        "LLM span should have ttft field set for async stream context manager"
    )
    assert isinstance(llm_span.ttft, (int, float)), (
        f"ttft should be a number, got {type(llm_span.ttft)}"
    )
    assert llm_span.ttft >= 0, f"ttft should be non-negative, got {llm_span.ttft}"
    assert llm_span.ttft < MAX_REASONABLE_TTFT_MS, (
        f"ttft should be reasonable (< {MAX_REASONABLE_TTFT_MS}ms), got {llm_span.ttft}"
    )
