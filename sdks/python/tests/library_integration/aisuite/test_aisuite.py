from typing import Any, Dict

import aisuite
import openai
import pytest

import opik
from opik.integrations.aisuite import track_aisuite
from ...testlib import (
    ANY_BUT_NONE,
    ANY_DICT,
    ANY_STRING,
    SpanModel,
    TraceModel,
    assert_dict_has_keys,
    assert_equal,
)

pytestmark = pytest.mark.usefixtures("ensure_openai_configured")

PROJECT_NAME = "aisuite-integration-test"


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


def test_aisuite__openai_provider__client_chat_completions_create__happyflow(
    fake_backend,
):
    client = aisuite.Client()
    wrapped_client = track_aisuite(
        aisuite_client=client,
        project_name=PROJECT_NAME,
    )
    messages = [
        {"role": "system", "content": "You are a helpful assistant."},
        {"role": "user", "content": "Tell a fact"},
    ]

    _ = wrapped_client.chat.completions.create(
        model="openai:gpt-3.5-turbo",
        messages=messages,
        max_tokens=10,
    )

    opik.flush_tracker()

    EXPECTED_TRACE_TREE = TraceModel(
        id=ANY_BUT_NONE,
        name="chat_completion_create",
        input={"messages": messages},
        output={"choices": ANY_BUT_NONE},
        tags=["aisuite"],
        metadata=ANY_DICT,
        start_time=ANY_BUT_NONE,
        end_time=ANY_BUT_NONE,
        project_name=PROJECT_NAME,
        spans=[
            SpanModel(
                id=ANY_BUT_NONE,
                type="llm",
                name="chat_completion_create",
                input={"messages": messages},
                output={"choices": ANY_BUT_NONE},
                tags=["aisuite"],
                metadata=ANY_DICT,
                usage={
                    "prompt_tokens": ANY_BUT_NONE,
                    "completion_tokens": ANY_BUT_NONE,
                    "total_tokens": ANY_BUT_NONE,
                },
                start_time=ANY_BUT_NONE,
                end_time=ANY_BUT_NONE,
                project_name=PROJECT_NAME,
                spans=[],
                model=ANY_STRING(startswith="gpt-3.5-turbo"),
                provider="openai",
            )
        ],
    )

    assert len(fake_backend.trace_trees) == 1
    trace_tree = fake_backend.trace_trees[0]

    assert_equal(EXPECTED_TRACE_TREE, trace_tree)

    llm_span_metadata = trace_tree.spans[0].metadata
    _assert_metadata_contains_required_keys(llm_span_metadata)


def test_aisuite__nonopenai_provider__client_chat_completions_create__happyflow(
    fake_backend,
):
    client = aisuite.Client()
    wrapped_client = track_aisuite(
        aisuite_client=client,
        project_name=PROJECT_NAME,
    )
    messages = [
        {"role": "system", "content": "You are a helpful assistant."},
        {"role": "user", "content": "Tell a fact"},
    ]

    _ = wrapped_client.chat.completions.create(
        model="anthropic:claude-3-5-sonnet-latest",
        messages=messages,
        max_tokens=10,
    )

    opik.flush_tracker()

    EXPECTED_TRACE_TREE = TraceModel(
        id=ANY_BUT_NONE,
        name="chat_completion_create",
        input={"messages": messages},
        output={"choices": ANY_BUT_NONE},
        tags=["aisuite"],
        metadata=ANY_DICT,
        start_time=ANY_BUT_NONE,
        end_time=ANY_BUT_NONE,
        project_name=PROJECT_NAME,
        spans=[
            SpanModel(
                id=ANY_BUT_NONE,
                type="llm",
                name="chat_completion_create",
                input={"messages": messages},
                output={"choices": ANY_BUT_NONE},
                tags=["aisuite"],
                metadata=ANY_DICT,
                usage=None,
                start_time=ANY_BUT_NONE,
                end_time=ANY_BUT_NONE,
                project_name=PROJECT_NAME,
                spans=[],
                model=ANY_STRING(startswith="claude-3-5-sonnet"),
                provider="anthropic",
            )
        ],
    )

    assert len(fake_backend.trace_trees) == 1
    trace_tree = fake_backend.trace_trees[0]

    assert_equal(EXPECTED_TRACE_TREE, trace_tree)


def test_aisuite_client_chat_completions_create__create_raises_an_error__span_and_trace_finished_gracefully__error_info_is_logged(
    fake_backend,
):
    client = aisuite.Client()
    wrapped_client = track_aisuite(
        aisuite_client=client,
        project_name=PROJECT_NAME,
    )

    with pytest.raises(openai.BadRequestError):
        _ = wrapped_client.chat.completions.create(
            messages=None,
            model="openai:gpt-3.5-turbo",
        )

    opik.flush_tracker()

    EXPECTED_TRACE_TREE = TraceModel(
        id=ANY_BUT_NONE,
        name="chat_completion_create",
        input={"messages": None},
        output=None,
        tags=["aisuite"],
        metadata={
            "created_from": "aisuite",
            "type": "aisuite_chat",
            "model": "openai:gpt-3.5-turbo",
        },
        start_time=ANY_BUT_NONE,
        end_time=ANY_BUT_NONE,
        project_name=PROJECT_NAME,
        error_info={
            "exception_type": ANY_STRING(),
            "message": ANY_STRING(),
            "traceback": ANY_STRING(),
        },
        spans=[
            SpanModel(
                id=ANY_BUT_NONE,
                type="llm",
                name="chat_completion_create",
                input={"messages": None},
                output=None,
                tags=["aisuite"],
                metadata={
                    "created_from": "aisuite",
                    "type": "aisuite_chat",
                    "model": "openai:gpt-3.5-turbo",
                },
                usage=None,
                start_time=ANY_BUT_NONE,
                end_time=ANY_BUT_NONE,
                project_name=PROJECT_NAME,
                model=ANY_STRING(startswith="gpt-3.5-turbo"),
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


def test_aisuite_client_chat_completions_create__openai_call_made_in_another_tracked_function__openai_span_attached_to_existing_trace(
    fake_backend,
):
    messages = [
        {"role": "system", "content": "You are a helpful assistant."},
        {"role": "user", "content": "Tell a fact"},
    ]

    @opik.track(project_name=PROJECT_NAME)
    def f():
        client = aisuite.Client()
        wrapped_client = track_aisuite(
            aisuite_client=client,
            # we are trying to log span into another project, but parent's project name will be used
            project_name=f"{PROJECT_NAME}-nested-level",
        )

        _ = wrapped_client.chat.completions.create(
            model="openai:gpt-3.5-turbo",
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
        project_name=PROJECT_NAME,
        spans=[
            SpanModel(
                id=ANY_BUT_NONE,
                name="f",
                input={},
                output=None,
                start_time=ANY_BUT_NONE,
                end_time=ANY_BUT_NONE,
                project_name=PROJECT_NAME,
                model=None,
                provider=None,
                spans=[
                    SpanModel(
                        id=ANY_BUT_NONE,
                        type="llm",
                        name="chat_completion_create",
                        input={"messages": messages},
                        output={"choices": ANY_BUT_NONE},
                        tags=["aisuite"],
                        metadata=ANY_DICT,
                        usage={
                            "prompt_tokens": ANY_BUT_NONE,
                            "completion_tokens": ANY_BUT_NONE,
                            "total_tokens": ANY_BUT_NONE,
                        },
                        start_time=ANY_BUT_NONE,
                        end_time=ANY_BUT_NONE,
                        project_name=PROJECT_NAME,
                        spans=[],
                        model=ANY_STRING(startswith="gpt-3.5-turbo"),
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
