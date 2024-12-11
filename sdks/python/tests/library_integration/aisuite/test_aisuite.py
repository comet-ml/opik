import asyncio
from typing import Any, Dict, List

import aisuite
import pytest
from pydantic import BaseModel

import opik
from opik.config import OPIK_PROJECT_DEFAULT_NAME
from opik.integrations.aisuite import track_aisuite
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
        # (None, OPIK_PROJECT_DEFAULT_NAME),
        ("aisuite-integration-test", "aisuite-integration-test"),
    ],
)
def test_aisuite_client_chat_completions_create__happyflow(
    fake_backend, project_name, expected_project_name
):
    client = aisuite.Client()
    wrapped_client = track_aisuite(
        aisuite_client=client,
        project_name=project_name,
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
                model=ANY_STRING(startswith="gpt-3.5-turbo"),
                provider="openai",
            )
        ],
    )

    assert len(fake_backend.trace_trees) == 1
    trace_tree = fake_backend.trace_trees[0]

    # assert_equal(EXPECTED_TRACE_TREE, trace_tree)
    #
    # llm_span_metadata = trace_tree.spans[0].metadata
    # _assert_metadata_contains_required_keys(llm_span_metadata)


@pytest.mark.skip
def test_openai_client_chat_completions_create__create_raises_an_error__span_and_trace_finished_gracefully__error_info_is_logged(
    fake_backend,
):
    client = aisuite.Client()
    wrapped_client = track_aisuite(aisuite_client=client)

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


@pytest.mark.skip
def test_aisuite_client_chat_completions_create__openai_call_made_in_another_tracked_function__openai_span_attached_to_existing_trace(
    fake_backend,
):
    project_name = "aisuite-integration-test"

    messages = [
        {"role": "system", "content": "You are a helpful assistant."},
        {"role": "user", "content": "Tell a fact"},
    ]

    @opik.track(project_name=project_name)
    def f():
        client = aisuite.Client()
        wrapped_client = track_aisuite(
            aisuite_client=client,
            # we are trying to log span into another project, but parent's project name will be used
            project_name="aisuite-integration-test-nested-level",
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
                model=None,
                provider=None,
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
