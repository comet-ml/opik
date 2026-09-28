"""Offline tests for the TypeSafe AI integration.

They always run. Every client is built with an ``httpx2.MockTransport`` and a
non-routable base URL, so no test can reach the real API even when
``TYPESAFE_API_KEY`` is set (``test_typesafe.py`` covers the real API then).
"""

import asyncio
from typing import Any, Dict, Optional

import httpx2
import pytest
from typesafe_sdk import (
    AsyncTypeSafeClient,
    ChoiceAnswer,
    RetryPolicy,
    SystemOneResponse,
    TypeSafeAuthenticationError,
    TypeSafeClient,
)

import opik
from opik.config import OPIK_PROJECT_DEFAULT_NAME
from opik.integrations.typesafe import track_typesafe
from opik.types import LLMProvider

from ...testlib import (
    ANY_BUT_NONE,
    ANY_DICT,
    SpanModel,
    TraceModel,
    assert_equal,
)
from .constants import (
    EXPECTED_INPUT,
    MODEL,
    QUESTIONS,
    QUESTIONS_AS_DICTS,
    STATE,
)

FAKE_BASE_URL = "https://api.typesafe.invalid"

RESPONSE_BODY = {
    "model": MODEL,
    "usage": {"input_tokens": 120, "output_tokens": 12},
    "answers": {
        "category": {
            "type": "choice",
            "choice": "billing",
            "confidence": 0.9,
            "probabilities": {"billing": 0.9, "technical": 0.05, "other": 0.05},
        },
        "is_urgent": {"type": "noul", "noul": 0.97},
        "frustration": {
            "type": "score",
            "score": 1.7,
            "confidence": 0.8,
            "legend": {"0": "calm", "1": "annoyed", "2": "furious"},
            "probabilities": {"0": 0.1, "1": 0.1, "2": 0.8},
        },
    },
}
EXPECTED_OUTPUT = {"answers": RESPONSE_BODY["answers"]}
EXPECTED_USAGE_LOGGED = {
    "prompt_tokens": 120,
    "completion_tokens": 12,
    "total_tokens": 132,
    "original_usage.input_tokens": 120,
    "original_usage.output_tokens": 12,
}
# Request parameters other than state/questions and response pieces other than
# answers (model, usage) are logged as metadata on both the span and the trace.
EXPECTED_REQUEST_METADATA = {"created_from": "typesafe", "type": "typesafe_system_one"}
EXPECTED_METADATA = {
    **EXPECTED_REQUEST_METADATA,
    "model": MODEL,
    "usage": RESPONSE_BODY["usage"],
}


def _mock_transport(body: Dict[str, Any], status_code: int) -> httpx2.MockTransport:
    def handler(request: httpx2.Request) -> httpx2.Response:
        return httpx2.Response(
            status_code, json=body, headers={"x-typesafe-request-id": "req_test"}
        )

    return httpx2.MockTransport(handler)


def _client(
    body: Optional[Dict[str, Any]] = None, status_code: int = 200
) -> TypeSafeClient:
    return TypeSafeClient(
        api_key="fake-api-key",
        base_url=FAKE_BASE_URL,
        transport=_mock_transport(RESPONSE_BODY if body is None else body, status_code),
        retry=RetryPolicy(max_retries=0),
    )


def _async_client() -> AsyncTypeSafeClient:
    return AsyncTypeSafeClient(
        api_key="fake-api-key",
        base_url=FAKE_BASE_URL,
        transport=_mock_transport(RESPONSE_BODY, 200),
        retry=RetryPolicy(max_retries=0),
    )


def _expected_trace_tree(
    project_name: str = OPIK_PROJECT_DEFAULT_NAME,
    input: Any = EXPECTED_INPUT,
    output: Any = EXPECTED_OUTPUT,
    metadata: Any = EXPECTED_METADATA,
    usage: Any = EXPECTED_USAGE_LOGGED,
    model: Any = MODEL,
    provider: str = "typesafe",
) -> TraceModel:
    return TraceModel(
        id=ANY_BUT_NONE,
        name="system_one",
        input=input,
        output=output,
        tags=["typesafe"],
        metadata=metadata,
        start_time=ANY_BUT_NONE,
        end_time=ANY_BUT_NONE,
        last_updated_at=ANY_BUT_NONE,
        project_name=project_name,
        spans=[
            SpanModel(
                id=ANY_BUT_NONE,
                type="llm",
                name="system_one",
                input=input,
                output=output,
                tags=["typesafe"],
                metadata=metadata,
                usage=usage,
                start_time=ANY_BUT_NONE,
                end_time=ANY_BUT_NONE,
                project_name=project_name,
                spans=[],
                model=model,
                provider=provider,
                source="sdk",
            )
        ],
        source="sdk",
    )


@pytest.mark.parametrize(
    "project_name, expected_project_name",
    [
        (None, OPIK_PROJECT_DEFAULT_NAME),
        ("typesafe-integration-test", "typesafe-integration-test"),
    ],
)
def test_typesafe_system_one__happyflow(
    fake_backend, project_name, expected_project_name
):
    client = track_typesafe(_client(), project_name=project_name)

    response = client.system_one(state=STATE, questions=QUESTIONS, model=MODEL)

    opik.flush_tracker()

    assert response.choices["category"].choice == "billing"

    EXPECTED_TRACE_TREE = _expected_trace_tree(project_name=expected_project_name)

    assert len(fake_backend.trace_trees) == 1
    assert_equal(EXPECTED_TRACE_TREE, fake_backend.trace_trees[0])


def test_typesafe_system_one__async__happyflow(fake_backend):
    client = track_typesafe(_async_client())

    async def run() -> SystemOneResponse:
        async with client:
            return await client.system_one(state=STATE, questions=QUESTIONS)

    response = asyncio.run(run())

    opik.flush_tracker()

    assert response.nouls["is_urgent"].noul == 0.97

    assert len(fake_backend.trace_trees) == 1
    assert_equal(_expected_trace_tree(), fake_backend.trace_trees[0])


def test_typesafe_system_one__positional_arguments__input_logged(fake_backend):
    client = track_typesafe(_client())

    client.system_one(STATE, QUESTIONS)

    opik.flush_tracker()

    # No explicit model in the request: the span model comes from the response.
    assert len(fake_backend.trace_trees) == 1
    assert_equal(_expected_trace_tree(), fake_backend.trace_trees[0])


def test_typesafe_system_one__questions_passed_as_dicts__input_logged(fake_backend):
    client = track_typesafe(_client())

    client.system_one(state=STATE, questions=QUESTIONS_AS_DICTS)

    opik.flush_tracker()

    EXPECTED_TRACE_TREE = _expected_trace_tree(
        input={"state": STATE, "questions": QUESTIONS_AS_DICTS}
    )

    assert len(fake_backend.trace_trees) == 1
    assert_equal(EXPECTED_TRACE_TREE, fake_backend.trace_trees[0])


def test_typesafe_system_one__custom_response_model__output_logged(fake_backend):
    class TicketAnswers(SystemOneResponse):
        category: ChoiceAnswer

    client = track_typesafe(_client())

    response = client.system_one(
        state=STATE, questions=QUESTIONS, response_model=TicketAnswers
    )

    opik.flush_tracker()

    assert response.category.choice == "billing"

    # Fields lifted onto the custom model are logged as metadata, `answers` stays the output.
    EXPECTED_TRACE_TREE = _expected_trace_tree(
        metadata={
            **EXPECTED_METADATA,
            "category": RESPONSE_BODY["answers"]["category"],
        },
    )

    assert len(fake_backend.trace_trees) == 1
    assert_equal(EXPECTED_TRACE_TREE, fake_backend.trace_trees[0])


def test_typesafe_system_one__extra_body_passed__logged_as_metadata(fake_backend):
    client = track_typesafe(_client())

    client.system_one(state=STATE, questions=QUESTIONS, extra_body={"beta": True})

    opik.flush_tracker()

    EXPECTED_TRACE_TREE = _expected_trace_tree(
        metadata={**EXPECTED_METADATA, "extra_body": {"beta": True}},
    )

    assert len(fake_backend.trace_trees) == 1
    assert_equal(EXPECTED_TRACE_TREE, fake_backend.trace_trees[0])


def test_typesafe_system_one__transport_options_passed__retry_and_timeout_in_metadata__headers_not_logged(
    fake_backend,
):
    client = track_typesafe(_client())
    timeout = httpx2.Timeout(5.0)
    retry = RetryPolicy(max_retries=0, http_statuses={429, 503})

    client.system_one(
        state=STATE,
        questions=QUESTIONS,
        timeout=timeout,
        retry=retry,
        extra_headers={"X-Secret": "do-not-log"},
    )

    opik.flush_tracker()

    expected_transport_metadata = {
        "timeout": {"connect": 5.0, "read": 5.0, "write": 5.0, "pool": 5.0},
        "retry": ANY_DICT.containing({"max_retries": 0, "http_statuses": [429, 503]}),
    }
    # Exact input dict and no `extra_headers` key anywhere in the metadata.
    EXPECTED_TRACE_TREE = _expected_trace_tree(
        metadata={**EXPECTED_METADATA, **expected_transport_metadata},
    )

    assert len(fake_backend.trace_trees) == 1
    assert_equal(EXPECTED_TRACE_TREE, fake_backend.trace_trees[0])


def test_typesafe_system_one__usage_missing__span_logged_without_usage(fake_backend):
    client = track_typesafe(_client(body={**RESPONSE_BODY, "usage": {}}))

    client.system_one(state=STATE, questions=QUESTIONS)

    opik.flush_tracker()

    EXPECTED_TRACE_TREE = _expected_trace_tree(
        metadata={
            **EXPECTED_METADATA,
            "usage": {"input_tokens": None, "output_tokens": None},
        },
        usage=None,
    )

    assert len(fake_backend.trace_trees) == 1
    assert_equal(EXPECTED_TRACE_TREE, fake_backend.trace_trees[0])


@pytest.mark.parametrize(
    "provider, expected_provider",
    [
        ("my-typesafe-proxy", "my-typesafe-proxy"),
        (LLMProvider.OPENAI, "openai"),
    ],
)
def test_typesafe_system_one__custom_provider__provider_logged_but_usage_still_parsed(
    fake_backend, provider, expected_provider
):
    client = track_typesafe(_client(), provider=provider)

    client.system_one(state=STATE, questions=QUESTIONS)

    opik.flush_tracker()

    EXPECTED_TRACE_TREE = _expected_trace_tree(provider=expected_provider)

    assert len(fake_backend.trace_trees) == 1
    assert_equal(EXPECTED_TRACE_TREE, fake_backend.trace_trees[0])


def test_typesafe_system_one__error_raised__span_and_trace_finished__error_info_logged(
    fake_backend,
):
    client = track_typesafe(_client(body={"error": "Invalid API key"}, status_code=401))

    with pytest.raises(TypeSafeAuthenticationError):
        client.system_one(state=STATE, questions=QUESTIONS)

    opik.flush_tracker()

    EXPECTED_ERROR_INFO = {
        "exception_type": "TypeSafeAuthenticationError",
        "message": ANY_BUT_NONE,
        "traceback": ANY_BUT_NONE,
    }

    EXPECTED_TRACE_TREE = TraceModel(
        id=ANY_BUT_NONE,
        name="system_one",
        input=EXPECTED_INPUT,
        output=None,
        tags=["typesafe"],
        metadata=EXPECTED_REQUEST_METADATA,
        error_info=EXPECTED_ERROR_INFO,
        start_time=ANY_BUT_NONE,
        end_time=ANY_BUT_NONE,
        last_updated_at=ANY_BUT_NONE,
        project_name=OPIK_PROJECT_DEFAULT_NAME,
        spans=[
            SpanModel(
                id=ANY_BUT_NONE,
                type="llm",
                name="system_one",
                input=EXPECTED_INPUT,
                output=None,
                tags=["typesafe"],
                metadata=EXPECTED_REQUEST_METADATA,
                error_info=EXPECTED_ERROR_INFO,
                start_time=ANY_BUT_NONE,
                end_time=ANY_BUT_NONE,
                project_name=OPIK_PROJECT_DEFAULT_NAME,
                spans=[],
                model=None,
                provider="typesafe",
                source="sdk",
            )
        ],
        source="sdk",
    )

    assert len(fake_backend.trace_trees) == 1
    assert_equal(EXPECTED_TRACE_TREE, fake_backend.trace_trees[0])


def test_typesafe_system_one__called_in_tracked_function__span_nested_under_track(
    fake_backend,
):
    project_name = "typesafe-integration-test"
    client = track_typesafe(_client())

    @opik.track(project_name=project_name)
    def f():
        client.system_one(state=STATE, questions=QUESTIONS)

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
                        name="system_one",
                        input=EXPECTED_INPUT,
                        output=EXPECTED_OUTPUT,
                        tags=["typesafe"],
                        metadata=EXPECTED_METADATA,
                        usage=EXPECTED_USAGE_LOGGED,
                        start_time=ANY_BUT_NONE,
                        end_time=ANY_BUT_NONE,
                        project_name=project_name,
                        spans=[],
                        model=MODEL,
                        provider="typesafe",
                        source="sdk",
                    )
                ],
                source="sdk",
            )
        ],
        source="sdk",
    )

    assert len(fake_backend.trace_trees) == 1
    assert_equal(EXPECTED_TRACE_TREE, fake_backend.trace_trees[0])


def test_track_typesafe__called_twice__client_patched_once(fake_backend):
    client = _client()
    assert track_typesafe(client) is client
    assert track_typesafe(client) is client

    client.system_one(state=STATE, questions=QUESTIONS)

    opik.flush_tracker()

    assert len(fake_backend.trace_trees) == 1
    assert_equal(_expected_trace_tree(), fake_backend.trace_trees[0])


def test_typesafe_system_one__tracing_disabled__call_succeeds_and_nothing_logged(
    fake_backend,
):
    client = track_typesafe(_client())
    opik.set_tracing_active(False)

    response = client.system_one(state=STATE, questions=QUESTIONS)

    opik.flush_tracker()

    assert response.choices["category"].choice == "billing"
    assert len(fake_backend.trace_trees) == 0


def test_track_typesafe__unsupported_old_version__raises(monkeypatch):
    from opik.integrations.typesafe import opik_tracker

    monkeypatch.setattr(
        opik_tracker.importlib.metadata, "version", lambda _pkg: "0.6.0"
    )

    with pytest.raises(RuntimeError, match=r"typesafe-sdk>=0\.7\.0"):
        track_typesafe(_client())
