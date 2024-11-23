import pytest
import openai
import os
import asyncio
from pydantic import BaseModel

import opik
from opik.integrations.openai import track_openai
from opik.config import OPIK_PROJECT_DEFAULT_NAME
from ...testlib import (
    SpanModel,
    TraceModel,
    ANY_BUT_NONE,
    ANY_DICT,
    assert_equal,
    assert_dict_has_keys,
)


# TODO: improve metadata checks


@pytest.fixture(autouse=True)
def ensure_openai_configured():
    # don't use assertion here to prevent printing os.environ with all env variables

    if not ("OPENAI_API_KEY" in os.environ and "OPENAI_ORG_ID" in os.environ):
        raise Exception("OpenAI not configured!")


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
        model="gpt-3.5-turbo",
        messages=messages,
        max_tokens=10,
    )

    opik.flush_tracker()

    EXPECTED_TRACE_TREE = TraceModel(
        id=ANY_BUT_NONE,
        name="chat_completion_create",
        input={"messages": messages},
        output={"choices": ANY_BUT_NONE},
        tags=["openai"],
        metadata=ANY_DICT,
        start_time=ANY_BUT_NONE,
        end_time=ANY_BUT_NONE,
        project_name=expected_project_name,
        spans=[
            SpanModel(
                id=ANY_BUT_NONE,
                type="llm",
                name="chat_completion_create",
                input={"messages": messages},
                output={"choices": ANY_BUT_NONE},
                tags=["openai"],
                metadata=ANY_DICT,
                usage={
                    "prompt_tokens": ANY_BUT_NONE,
                    "completion_tokens": ANY_BUT_NONE,
                    "total_tokens": ANY_BUT_NONE,
                },
                start_time=ANY_BUT_NONE,
                end_time=ANY_BUT_NONE,
                project_name=expected_project_name,
                spans=[],
            )
        ],
    )

    assert len(fake_backend.trace_trees) == 1
    trace_tree = fake_backend.trace_trees[0]

    assert_equal(EXPECTED_TRACE_TREE, trace_tree)

    llm_span_metadata = trace_tree.spans[0].metadata
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
    assert_dict_has_keys(llm_span_metadata, REQUIRED_METADATA_KEYS)


def test_openai_client_chat_completions_create__create_raises_an_error__span_and_trace_finished_gracefully(
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
        input={"messages": None},
        output=None,
        tags=["openai"],
        metadata={
            "created_from": "openai",
            "type": "openai_chat",
            "model": None,
        },
        start_time=ANY_BUT_NONE,
        end_time=ANY_BUT_NONE,
        project_name=ANY_BUT_NONE,
        spans=[
            SpanModel(
                id=ANY_BUT_NONE,
                type="llm",
                name="chat_completion_create",
                input={"messages": None},
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
            model="gpt-3.5-turbo",
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
                spans=[
                    SpanModel(
                        id=ANY_BUT_NONE,
                        type="llm",
                        name="chat_completion_create",
                        input={"messages": messages},
                        output={"choices": ANY_BUT_NONE},
                        tags=["openai"],
                        metadata=ANY_DICT,
                        usage={
                            "prompt_tokens": ANY_BUT_NONE,
                            "completion_tokens": ANY_BUT_NONE,
                            "total_tokens": ANY_BUT_NONE,
                        },
                        start_time=ANY_BUT_NONE,
                        end_time=ANY_BUT_NONE,
                        project_name=project_name,
                        spans=[],
                    )
                ],
            )
        ],
    )

    assert len(fake_backend.trace_trees) == 1

    trace_tree = fake_backend.trace_trees[0]

    assert_equal(EXPECTED_TRACE_TREE, trace_tree)

    llm_span_metadata = trace_tree.spans[0].spans[0].metadata
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
    assert_dict_has_keys(llm_span_metadata, REQUIRED_METADATA_KEYS)


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
            model="gpt-3.5-turbo",
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
                spans=[
                    SpanModel(
                        id=ANY_BUT_NONE,
                        type="llm",
                        name="chat_completion_create",
                        input={"messages": messages},
                        output={"choices": ANY_BUT_NONE},
                        tags=["openai"],
                        metadata=ANY_DICT,
                        usage={
                            "prompt_tokens": ANY_BUT_NONE,
                            "completion_tokens": ANY_BUT_NONE,
                            "total_tokens": ANY_BUT_NONE,
                        },
                        start_time=ANY_BUT_NONE,
                        end_time=ANY_BUT_NONE,
                        project_name=ANY_BUT_NONE,
                        spans=[],
                    )
                ],
            )
        ],
    )

    assert len(fake_backend.trace_trees) == 1
    trace_tree = fake_backend.trace_trees[0]

    assert_equal(EXPECTED_TRACE_TREE, trace_tree)

    llm_span_metadata = trace_tree.spans[0].spans[0].metadata
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
    assert_dict_has_keys(llm_span_metadata, REQUIRED_METADATA_KEYS)


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
        model="gpt-3.5-turbo",
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
        name="chat_completion_create",
        input={"messages": messages},
        output={"choices": ANY_BUT_NONE},
        tags=["openai"],
        metadata=ANY_DICT,
        start_time=ANY_BUT_NONE,
        end_time=ANY_BUT_NONE,
        project_name=ANY_BUT_NONE,
        spans=[
            SpanModel(
                id=ANY_BUT_NONE,
                type="llm",
                name="chat_completion_create",
                input={"messages": messages},
                output={"choices": ANY_BUT_NONE},
                tags=["openai"],
                metadata=ANY_DICT,
                usage={
                    "prompt_tokens": ANY_BUT_NONE,
                    "completion_tokens": ANY_BUT_NONE,
                    "total_tokens": ANY_BUT_NONE,
                },
                start_time=ANY_BUT_NONE,
                end_time=ANY_BUT_NONE,
                project_name=ANY_BUT_NONE,
                spans=[],
            )
        ],
    )

    assert len(fake_backend.trace_trees) == 1
    trace_tree = fake_backend.trace_trees[0]

    assert_equal(EXPECTED_TRACE_TREE, trace_tree)

    llm_span_metadata = trace_tree.spans[0].metadata
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
    assert_dict_has_keys(llm_span_metadata, REQUIRED_METADATA_KEYS)


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
            model="gpt-3.5-turbo",
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
                spans=[
                    SpanModel(
                        id=ANY_BUT_NONE,
                        type="llm",
                        name="chat_completion_create",
                        input={"messages": messages},
                        output={"choices": ANY_BUT_NONE},
                        tags=["openai"],
                        metadata=ANY_DICT,
                        usage={
                            "prompt_tokens": ANY_BUT_NONE,
                            "completion_tokens": ANY_BUT_NONE,
                            "total_tokens": ANY_BUT_NONE,
                        },
                        start_time=ANY_BUT_NONE,
                        end_time=ANY_BUT_NONE,
                        project_name=ANY_BUT_NONE,
                        spans=[],
                    )
                ],
            )
        ],
    )

    assert len(fake_backend.trace_trees) == 1
    trace_tree = fake_backend.trace_trees[0]

    assert_equal(EXPECTED_TRACE_TREE, trace_tree)

    llm_span_metadata = trace_tree.spans[0].spans[0].metadata
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
    assert_dict_has_keys(llm_span_metadata, REQUIRED_METADATA_KEYS)


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
        participants: list[str]

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
                usage={
                    "prompt_tokens": ANY_BUT_NONE,
                    "completion_tokens": ANY_BUT_NONE,
                    "total_tokens": ANY_BUT_NONE,
                },
                start_time=ANY_BUT_NONE,
                end_time=ANY_BUT_NONE,
                project_name=expected_project_name,
                spans=[],
            )
        ],
    )

    assert len(fake_backend.trace_trees) == 1
    trace_tree = fake_backend.trace_trees[0]

    assert_equal(EXPECTED_TRACE_TREE, trace_tree)

    llm_span_metadata = trace_tree.spans[0].metadata
    REQUIRED_METADATA_KEYS = [
        "usage",
        "model",
        "created_from",
        "type",
        "id",
        "created",
        "object",
    ]
    assert_dict_has_keys(llm_span_metadata, REQUIRED_METADATA_KEYS)
