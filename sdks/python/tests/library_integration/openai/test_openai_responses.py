from typing import Any, Dict

import openai
import pydantic
import pytest

import opik
from opik.config import OPIK_PROJECT_DEFAULT_NAME
from opik.integrations.openai import track_openai
from opik.types import ErrorInfoDict

from .constants import MODEL_FOR_TESTS, EXPECTED_OPENAI_USAGE_LOGGED_FORMAT
from ...testlib import (
    ANY,
    ANY_BUT_NONE,
    ANY_DICT,
    ANY_STRING,
    SpanModel,
    TraceModel,
    assert_dict_has_keys,
    assert_equal,
)


@pytest.fixture(autouse=True)
def check_openai_configured(ensure_openai_configured):
    pass


def _assert_metadata_contains_required_keys(metadata: Dict[str, Any]):
    REQUIRED_METADATA_KEYS = [
        "usage",
        "model",
        "max_output_tokens",
        "created_from",
        "type",
        "id",
    ]
    assert_dict_has_keys(metadata, REQUIRED_METADATA_KEYS)


@pytest.mark.parametrize(
    "project_name, expected_project_name",
    [
        (None, OPIK_PROJECT_DEFAULT_NAME),
        ("openai-integration-test", "openai-integration-test"),
    ],
)
def test_openai_client_responses_create__happyflow(
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

    _ = wrapped_client.responses.create(
        model=MODEL_FOR_TESTS,
        input=messages,
        max_output_tokens=50,
    )

    opik.flush_tracker()

    EXPECTED_TRACE_TREE = TraceModel(
        id=ANY_BUT_NONE,
        name="responses_create",
        input={"input": messages},
        output={"output": ANY_BUT_NONE, "reasoning": ANY},
        tags=["openai"],
        metadata=ANY_DICT,
        start_time=ANY_BUT_NONE,
        end_time=ANY_BUT_NONE,
        project_name=expected_project_name,
        spans=[
            SpanModel(
                id=ANY_BUT_NONE,
                type="llm",
                name="responses_create",
                input={"input": messages},
                output={"output": ANY_BUT_NONE, "reasoning": ANY},
                tags=["openai"],
                metadata=ANY_DICT,
                usage=EXPECTED_OPENAI_USAGE_LOGGED_FORMAT,
                start_time=ANY_BUT_NONE,
                end_time=ANY_BUT_NONE,
                project_name=expected_project_name,
                spans=[],
                model=ANY_STRING(startswith=MODEL_FOR_TESTS),
                provider="openai",
            )
        ],
    )

    assert len(fake_backend.trace_trees) == 1
    trace_tree = fake_backend.trace_trees[0]

    assert_equal(EXPECTED_TRACE_TREE, trace_tree)

    llm_span_metadata = trace_tree.spans[0].metadata
    _assert_metadata_contains_required_keys(llm_span_metadata)


def test_openai_responses_create__async_call_made_in_another_tracked_async_function__openai_span_attached_to_existing_trace(
    fake_backend,
):
    client = openai.OpenAI()
    wrapped_client = track_openai(openai_client=client)
    messages = [
        {"role": "system", "content": "You are a helpful assistant."},
        {"role": "user", "content": "Tell a fact"},
    ]

    @opik.track
    def f():
        _ = wrapped_client.responses.create(
            model=MODEL_FOR_TESTS,
            input=messages,
            max_output_tokens=50,
        )

    f()

    opik.flush_tracker()

    EXPECTED_TRACE_TREE = TraceModel(
        id=ANY_BUT_NONE,
        name="f",
        input={},
        start_time=ANY_BUT_NONE,
        end_time=ANY_BUT_NONE,
        spans=[
            SpanModel(
                id=ANY_BUT_NONE,
                name="f",
                input={},
                start_time=ANY_BUT_NONE,
                end_time=ANY_BUT_NONE,
                spans=[
                    SpanModel(
                        id=ANY_BUT_NONE,
                        type="llm",
                        name="responses_create",
                        input={"input": messages},
                        output={"output": ANY_BUT_NONE, "reasoning": ANY},
                        tags=["openai"],
                        metadata=ANY_DICT,
                        usage=EXPECTED_OPENAI_USAGE_LOGGED_FORMAT,
                        start_time=ANY_BUT_NONE,
                        end_time=ANY_BUT_NONE,
                        spans=[],
                        model=ANY_STRING(startswith=MODEL_FOR_TESTS),
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


def test_openai_client_responses_create_raises_an_error__span_and_trace_finished_gracefully__error_info_is_logged(
    fake_backend,
):
    client = openai.OpenAI()
    wrapped_client = track_openai(openai_client=client)
    messages = [
        {"role": "system", "content": "You are a helpful assistant."},
        {"role": "user", "content": "Tell a fact"},
    ]

    with pytest.raises(openai.OpenAIError):
        _ = wrapped_client.responses.create(
            model=MODEL_FOR_TESTS,
            input=messages,
            max_output_tokens=-1,
        )

    opik.flush_tracker()
    EXPECTED_TRACE_TREE = TraceModel(
        id=ANY_BUT_NONE,
        name="responses_create",
        input={"input": messages},
        output=None,
        tags=["openai"],
        metadata={
            "created_from": "openai",
            "type": "openai_responses",
            "max_output_tokens": -1,
            "model": MODEL_FOR_TESTS,
        },
        start_time=ANY_BUT_NONE,
        end_time=ANY_BUT_NONE,
        project_name=ANY_BUT_NONE,
        error_info={
            "exception_type": ANY_STRING(),
            "message": ANY_STRING(),
            "traceback": ANY_STRING(),
        },
        spans=[
            SpanModel(
                id=ANY_BUT_NONE,
                type="llm",
                name="responses_create",
                input={"input": messages},
                output=None,
                tags=["openai"],
                metadata={
                    "created_from": "openai",
                    "type": "openai_responses",
                    "model": MODEL_FOR_TESTS,
                    "max_output_tokens": -1,
                },
                usage=None,
                start_time=ANY_BUT_NONE,
                end_time=ANY_BUT_NONE,
                project_name=ANY_BUT_NONE,
                model=MODEL_FOR_TESTS,
                provider="openai",
                error_info={
                    "exception_type": ANY_STRING(),
                    "message": ANY_STRING(),
                    "traceback": ANY_STRING(),
                },
                spans=[],
            )
        ],
    )

    assert len(fake_backend.trace_trees) == 1

    trace_tree = fake_backend.trace_trees[0]
    assert_equal(EXPECTED_TRACE_TREE, trace_tree)


def test_openai_client_responses_create_stream__happyflow(fake_backend):
    client = openai.OpenAI()
    wrapped_client = track_openai(openai_client=client)
    messages = [
        {"role": "system", "content": "You are a helpful assistant."},
        {"role": "user", "content": "Tell a fact"},
    ]

    stream = wrapped_client.responses.create(
        model=MODEL_FOR_TESTS,
        input=messages,
        max_output_tokens=16,
        stream=True,
    )

    for _ in stream:
        pass

    opik.flush_tracker()

    EXPECTED_TRACE_TREE = TraceModel(
        id=ANY_BUT_NONE,
        name="responses_create",
        input={"input": messages},
        output={"output": ANY_BUT_NONE, "reasoning": ANY},
        tags=["openai"],
        metadata=ANY_DICT,
        start_time=ANY_BUT_NONE,
        end_time=ANY_BUT_NONE,
        spans=[
            SpanModel(
                id=ANY_BUT_NONE,
                type="llm",
                name="responses_create",
                input={"input": messages},
                output={"output": ANY_BUT_NONE, "reasoning": ANY},
                tags=["openai"],
                metadata=ANY_DICT,
                usage=EXPECTED_OPENAI_USAGE_LOGGED_FORMAT,
                start_time=ANY_BUT_NONE,
                end_time=ANY_BUT_NONE,
                spans=[],
                model=ANY_STRING(startswith=MODEL_FOR_TESTS),
                provider="openai",
            )
        ],
    )

    assert len(fake_backend.trace_trees) == 1
    trace_tree = fake_backend.trace_trees[0]

    assert_equal(EXPECTED_TRACE_TREE, trace_tree)

    llm_span_metadata = trace_tree.spans[0].metadata
    _assert_metadata_contains_required_keys(llm_span_metadata)


@pytest.mark.asyncio
async def test_openai_client_responses_create_async__happyflow(fake_backend):
    client = openai.AsyncOpenAI()
    wrapped_client = track_openai(
        openai_client=client,
    )
    messages = [
        {"role": "system", "content": "You are a helpful assistant."},
        {"role": "user", "content": "Tell a fact"},
    ]

    _ = await wrapped_client.responses.create(
        model=MODEL_FOR_TESTS,
        input=messages,
        max_output_tokens=50,
    )

    opik.flush_tracker()

    EXPECTED_TRACE_TREE = TraceModel(
        id=ANY_BUT_NONE,
        name="responses_create",
        input={"input": messages},
        output={"output": ANY_BUT_NONE, "reasoning": ANY},
        tags=["openai"],
        metadata=ANY_DICT,
        start_time=ANY_BUT_NONE,
        end_time=ANY_BUT_NONE,
        spans=[
            SpanModel(
                id=ANY_BUT_NONE,
                type="llm",
                name="responses_create",
                input={"input": messages},
                output={"output": ANY_BUT_NONE, "reasoning": ANY},
                tags=["openai"],
                metadata=ANY_DICT,
                usage=EXPECTED_OPENAI_USAGE_LOGGED_FORMAT,
                start_time=ANY_BUT_NONE,
                end_time=ANY_BUT_NONE,
                spans=[],
                model=ANY_STRING(startswith=MODEL_FOR_TESTS),
                provider="openai",
            )
        ],
    )

    assert len(fake_backend.trace_trees) == 1
    trace_tree = fake_backend.trace_trees[0]

    assert_equal(EXPECTED_TRACE_TREE, trace_tree)

    llm_span_metadata = trace_tree.spans[0].metadata
    _assert_metadata_contains_required_keys(llm_span_metadata)


@pytest.mark.asyncio
async def test_openai_client_responses_create_stream_async__happyflow(fake_backend):
    client = openai.AsyncOpenAI()
    wrapped_client = track_openai(openai_client=client)
    messages = [
        {"role": "system", "content": "You are a helpful assistant."},
        {"role": "user", "content": "Tell a fact"},
    ]

    stream = await wrapped_client.responses.create(
        model=MODEL_FOR_TESTS,
        input=messages,
        max_output_tokens=50,
        stream=True,
    )

    async for _ in stream:
        pass

    opik.flush_tracker()

    EXPECTED_TRACE_TREE = TraceModel(
        id=ANY_BUT_NONE,
        name="responses_create",
        input={"input": messages},
        output={"output": ANY_BUT_NONE, "reasoning": ANY},
        tags=["openai"],
        metadata=ANY_DICT,
        start_time=ANY_BUT_NONE,
        end_time=ANY_BUT_NONE,
        spans=[
            SpanModel(
                id=ANY_BUT_NONE,
                type="llm",
                name="responses_create",
                input={"input": messages},
                output={"output": ANY_BUT_NONE, "reasoning": ANY},
                tags=["openai"],
                metadata=ANY_DICT,
                usage=EXPECTED_OPENAI_USAGE_LOGGED_FORMAT,
                start_time=ANY_BUT_NONE,
                end_time=ANY_BUT_NONE,
                spans=[],
                model=ANY_STRING(startswith=MODEL_FOR_TESTS),
                provider="openai",
            )
        ],
    )

    assert len(fake_backend.trace_trees) == 1
    trace_tree = fake_backend.trace_trees[0]

    assert_equal(EXPECTED_TRACE_TREE, trace_tree)

    llm_span_metadata = trace_tree.spans[0].metadata
    _assert_metadata_contains_required_keys(llm_span_metadata)


@pytest.mark.parametrize(
    "project_name, expected_project_name",
    [
        (None, OPIK_PROJECT_DEFAULT_NAME),
        ("openai-integration-test", "openai-integration-test"),
    ],
)
def test_openai_client_responses_parse__happy_flow(
    fake_backend, project_name, expected_project_name
):
    client = openai.OpenAI()
    wrapped_client = track_openai(
        openai_client=client,
        project_name=project_name,
    )

    class CalendarEvent(pydantic.BaseModel):
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
    _ = wrapped_client.responses.parse(
        model=MODEL_FOR_TESTS,
        input=messages,
        text_format=CalendarEvent,
    )

    opik.flush_tracker()

    EXPECTED_TRACE_TREE = TraceModel(
        id=ANY_BUT_NONE,
        start_time=ANY_BUT_NONE,
        name="responses_parse",
        project_name=expected_project_name,
        input={"input": messages},
        output={"output": ANY_BUT_NONE, "reasoning": ANY},
        tags=["openai"],
        metadata=ANY_DICT,
        end_time=ANY_BUT_NONE,
        spans=[
            SpanModel(
                id=ANY_BUT_NONE,
                start_time=ANY_BUT_NONE,
                name="responses_parse",
                input={"input": messages},
                output={"output": ANY_BUT_NONE, "reasoning": ANY},
                tags=["openai"],
                metadata=ANY_DICT,
                type="llm",
                usage=EXPECTED_OPENAI_USAGE_LOGGED_FORMAT,
                end_time=ANY_BUT_NONE,
                project_name=expected_project_name,
                model=ANY_STRING(startswith=MODEL_FOR_TESTS),
                provider="openai",
            )
        ],
    )

    assert len(fake_backend.trace_trees) == 1
    trace_tree = fake_backend.trace_trees[0]
    print(trace_tree)

    assert_equal(EXPECTED_TRACE_TREE, trace_tree)

    llm_span_metadata = trace_tree.spans[0].metadata
    _assert_metadata_contains_required_keys(llm_span_metadata)


@pytest.mark.asyncio
async def test_openai_client_responses_parse_async__happy_flow(fake_backend):
    client = openai.AsyncOpenAI()
    wrapped_client = track_openai(
        openai_client=client,
    )

    class CalendarEvent(pydantic.BaseModel):
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
    _ = await wrapped_client.responses.parse(
        model=MODEL_FOR_TESTS,
        input=messages,
        text_format=CalendarEvent,
    )

    opik.flush_tracker()

    EXPECTED_TRACE_TREE = TraceModel(
        id=ANY_BUT_NONE,
        start_time=ANY_BUT_NONE,
        name="responses_parse",
        input={"input": messages},
        output={"output": ANY_BUT_NONE, "reasoning": ANY},
        tags=["openai"],
        metadata=ANY_DICT,
        end_time=ANY_BUT_NONE,
        spans=[
            SpanModel(
                id=ANY_BUT_NONE,
                start_time=ANY_BUT_NONE,
                name="responses_parse",
                input={"input": messages},
                output={"output": ANY_BUT_NONE, "reasoning": ANY},
                tags=["openai"],
                metadata=ANY_DICT,
                type="llm",
                usage=EXPECTED_OPENAI_USAGE_LOGGED_FORMAT,
                end_time=ANY_BUT_NONE,
                model=ANY_STRING(startswith=MODEL_FOR_TESTS),
                provider="openai",
            )
        ],
    )

    assert len(fake_backend.trace_trees) == 1
    trace_tree = fake_backend.trace_trees[0]
    print(trace_tree)

    assert_equal(EXPECTED_TRACE_TREE, trace_tree)

    llm_span_metadata = trace_tree.spans[0].metadata
    _assert_metadata_contains_required_keys(llm_span_metadata)


def test_openai_client_responses_parse_raises_an_error__span_and_trace_finished_gracefully__error_info_is_logged(
    fake_backend,
):
    client = openai.OpenAI()
    wrapped_client = track_openai(openai_client=client)

    class CalendarEvent(pydantic.BaseModel):
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

    with pytest.raises(openai.OpenAIError):
        _ = wrapped_client.responses.parse(
            model=MODEL_FOR_TESTS,
            input=messages,
            text_format=CalendarEvent,
            max_output_tokens=-1,
        )

    opik.flush_tracker()

    EXPECTED_TRACE_TREE = TraceModel(
        id=ANY_BUT_NONE,
        name="responses_parse",
        input={"input": messages},
        output=None,
        tags=["openai"],
        metadata={
            "created_from": "openai",
            "type": "openai_responses",
            "max_output_tokens": -1,
            "model": MODEL_FOR_TESTS,
            "text_format": ANY_BUT_NONE,
        },
        start_time=ANY_BUT_NONE,
        end_time=ANY_BUT_NONE,
        project_name=ANY_BUT_NONE,
        error_info=ErrorInfoDict(
            exception_type=ANY_STRING(),
            message=ANY_STRING(),
            traceback=ANY_STRING(),
        ),
        spans=[
            SpanModel(
                id=ANY_BUT_NONE,
                type="llm",
                name="responses_parse",
                input={"input": messages},
                output=None,
                tags=["openai"],
                metadata={
                    "created_from": "openai",
                    "type": "openai_responses",
                    "model": MODEL_FOR_TESTS,
                    "max_output_tokens": -1,
                    "text_format": ANY_BUT_NONE,
                },
                usage=None,
                start_time=ANY_BUT_NONE,
                end_time=ANY_BUT_NONE,
                project_name=ANY_BUT_NONE,
                model=MODEL_FOR_TESTS,
                provider="openai",
                error_info=ErrorInfoDict(
                    exception_type=ANY_STRING(),
                    message=ANY_STRING(),
                    traceback=ANY_STRING(),
                ),
                spans=[],
            )
        ],
    )

    assert len(fake_backend.trace_trees) == 1
    trace_tree = fake_backend.trace_trees[0]

    assert_equal(EXPECTED_TRACE_TREE, trace_tree)
