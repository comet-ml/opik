"""Offline tests for tracking `decisions.create()` with `track_openai`.

They always run. Every client is built with an ``httpx2.MockTransport``, which
answers all requests, so no test can reach the real API even when
``OPENAI_API_KEY`` is set (``test_openai_decisions.py`` covers the real API).
"""

import asyncio
from typing import Any, Dict

import httpx2
import openai
import pytest

import opik
from opik.config import OPIK_PROJECT_DEFAULT_NAME
from opik.integrations.openai import track_openai

from ...testlib import ANY_BUT_NONE, SpanModel, TraceModel, assert_equal
from .constants import (
    DECISIONS_INPUT_FOR_TESTS,
    DECISIONS_MODEL_FOR_TESTS,
    DECISIONS_QUESTIONS_FOR_TESTS,
)

RESPONSE_BODY = {
    "model": DECISIONS_MODEL_FOR_TESTS,
    "answers": [
        {"name": "is_complaint", "type": "predicate", "probability": 0.98},
        {
            "name": "topic",
            "type": "choice",
            "choice": "billing",
            "confidence": 0.93,
            "probabilities": [
                {"value": "billing", "probability": 0.93},
                {"value": "shipping", "probability": 0.02},
                {"value": "other", "probability": 0.05},
            ],
        },
        {
            "name": "frustration",
            "type": "score",
            "score": 1.1,
            "confidence": 0.55,
            "probabilities": [
                {"value": 0, "label": "calm", "probability": 0.1},
                {"value": 1, "label": "annoyed", "probability": 0.7},
                {"value": 2, "label": "furious", "probability": 0.2},
            ],
        },
    ],
    "usage": {
        "input_tokens": 120,
        "input_tokens_details": {"cache_write_tokens": 0, "cached_tokens": 20},
        "output_tokens": 0,
        "output_tokens_details": {"reasoning_tokens": 0},
        "total_tokens": 120,
    },
}

EXPECTED_INPUT = {
    "input": DECISIONS_INPUT_FOR_TESTS,
    "questions": DECISIONS_QUESTIONS_FOR_TESTS,
}
EXPECTED_OUTPUT = {"answers": RESPONSE_BODY["answers"]}
EXPECTED_USAGE_LOGGED = {
    "prompt_tokens": 120,
    "completion_tokens": 0,
    "total_tokens": 120,
    "original_usage.input_tokens": 120,
    "original_usage.output_tokens": 0,
    "original_usage.total_tokens": 120,
    "original_usage.input_tokens_details.cached_tokens": 20,
    "original_usage.input_tokens_details.cache_write_tokens": 0,
    "original_usage.output_tokens_details.reasoning_tokens": 0,
}
EXPECTED_REQUEST_METADATA = {
    "created_from": "openai",
    "type": "openai_decisions",
    "model": DECISIONS_MODEL_FOR_TESTS,
}
EXPECTED_METADATA = {**EXPECTED_REQUEST_METADATA, "usage": RESPONSE_BODY["usage"]}


def _mock_transport(body: Dict[str, Any], status_code: int) -> httpx2.MockTransport:
    def handler(request: httpx2.Request) -> httpx2.Response:
        return httpx2.Response(status_code, json=body)

    return httpx2.MockTransport(handler)


def _client(
    body: Dict[str, Any] = RESPONSE_BODY, status_code: int = 200
) -> openai.OpenAI:
    return openai.OpenAI(
        api_key="fake-api-key",
        max_retries=0,
        http_client=httpx2.Client(transport=_mock_transport(body, status_code)),
    )


def _async_client() -> openai.AsyncOpenAI:
    return openai.AsyncOpenAI(
        api_key="fake-api-key",
        max_retries=0,
        http_client=httpx2.AsyncClient(transport=_mock_transport(RESPONSE_BODY, 200)),
    )


def _llm_span(project_name: str) -> SpanModel:
    return SpanModel(
        id=ANY_BUT_NONE,
        type="llm",
        name="decisions_create",
        input=EXPECTED_INPUT,
        output=EXPECTED_OUTPUT,
        tags=["openai"],
        metadata=EXPECTED_METADATA,
        usage=EXPECTED_USAGE_LOGGED,
        start_time=ANY_BUT_NONE,
        end_time=ANY_BUT_NONE,
        project_name=project_name,
        spans=[],
        model=DECISIONS_MODEL_FOR_TESTS,
        provider="openai",
        source="sdk",
    )


def _expected_trace_tree(project_name: str) -> TraceModel:
    return TraceModel(
        id=ANY_BUT_NONE,
        name="decisions_create",
        input=EXPECTED_INPUT,
        output=EXPECTED_OUTPUT,
        tags=["openai"],
        metadata=EXPECTED_METADATA,
        start_time=ANY_BUT_NONE,
        end_time=ANY_BUT_NONE,
        last_updated_at=ANY_BUT_NONE,
        project_name=project_name,
        spans=[_llm_span(project_name)],
        source="sdk",
    )


@pytest.mark.parametrize(
    "project_name, expected_project_name",
    [
        (None, OPIK_PROJECT_DEFAULT_NAME),
        ("openai-integration-test", "openai-integration-test"),
    ],
)
def test_openai_decisions_create__happyflow(
    fake_backend, project_name, expected_project_name
):
    client = track_openai(_client(), project_name=project_name)

    decision = client.decisions.create(
        model=DECISIONS_MODEL_FOR_TESTS,
        input=DECISIONS_INPUT_FOR_TESTS,
        questions=DECISIONS_QUESTIONS_FOR_TESTS,
    )

    opik.flush_tracker()

    assert decision.answers[1].choice == "billing"

    assert len(fake_backend.trace_trees) == 1
    assert_equal(
        _expected_trace_tree(expected_project_name),
        fake_backend.trace_trees[0],
    )


def test_openai_decisions_create__async__happyflow(fake_backend):
    client = track_openai(_async_client())

    async def run() -> None:
        await client.decisions.create(
            model=DECISIONS_MODEL_FOR_TESTS,
            input=DECISIONS_INPUT_FOR_TESTS,
            questions=DECISIONS_QUESTIONS_FOR_TESTS,
        )

    asyncio.run(run())

    opik.flush_tracker()

    assert len(fake_backend.trace_trees) == 1
    assert_equal(
        _expected_trace_tree(OPIK_PROJECT_DEFAULT_NAME), fake_backend.trace_trees[0]
    )


def test_openai_decisions_create__error_raised__span_and_trace_finished__error_info_logged(
    fake_backend,
):
    client = track_openai(
        _client(body={"error": {"message": "Invalid API key"}}, status_code=401)
    )

    with pytest.raises(openai.AuthenticationError):
        client.decisions.create(
            model=DECISIONS_MODEL_FOR_TESTS,
            input=DECISIONS_INPUT_FOR_TESTS,
            questions=DECISIONS_QUESTIONS_FOR_TESTS,
        )

    opik.flush_tracker()

    EXPECTED_ERROR_INFO = {
        "exception_type": "AuthenticationError",
        "message": ANY_BUT_NONE,
        "traceback": ANY_BUT_NONE,
    }

    EXPECTED_TRACE_TREE = TraceModel(
        id=ANY_BUT_NONE,
        name="decisions_create",
        input=EXPECTED_INPUT,
        output=None,
        tags=["openai"],
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
                name="decisions_create",
                input=EXPECTED_INPUT,
                output=None,
                tags=["openai"],
                metadata=EXPECTED_REQUEST_METADATA,
                error_info=EXPECTED_ERROR_INFO,
                start_time=ANY_BUT_NONE,
                end_time=ANY_BUT_NONE,
                project_name=OPIK_PROJECT_DEFAULT_NAME,
                spans=[],
                model=DECISIONS_MODEL_FOR_TESTS,
                provider="openai",
                source="sdk",
            )
        ],
        source="sdk",
    )

    assert len(fake_backend.trace_trees) == 1
    assert_equal(EXPECTED_TRACE_TREE, fake_backend.trace_trees[0])


def test_openai_decisions_create__called_in_tracked_function__span_nested_under_track(
    fake_backend,
):
    project_name = "openai-integration-test"
    client = track_openai(_client())

    @opik.track(project_name=project_name)
    def f():
        client.decisions.create(
            model=DECISIONS_MODEL_FOR_TESTS,
            input=DECISIONS_INPUT_FOR_TESTS,
            questions=DECISIONS_QUESTIONS_FOR_TESTS,
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
                spans=[_llm_span(project_name)],
                source="sdk",
            )
        ],
        source="sdk",
    )

    assert len(fake_backend.trace_trees) == 1
    assert_equal(EXPECTED_TRACE_TREE, fake_backend.trace_trees[0])


def test_track_openai__called_twice__decisions_create_tracked_once(fake_backend):
    client = _client()
    assert track_openai(client) is client
    assert track_openai(client) is client

    client.decisions.create(
        model=DECISIONS_MODEL_FOR_TESTS,
        input=DECISIONS_INPUT_FOR_TESTS,
        questions=DECISIONS_QUESTIONS_FOR_TESTS,
    )

    opik.flush_tracker()

    assert len(fake_backend.trace_trees) == 1
    assert_equal(
        _expected_trace_tree(OPIK_PROJECT_DEFAULT_NAME), fake_backend.trace_trees[0]
    )


def test_openai_decisions_create__tracing_disabled__call_succeeds_and_nothing_logged(
    fake_backend,
):
    client = track_openai(_client())
    opik.set_tracing_active(False)

    decision = client.decisions.create(
        model=DECISIONS_MODEL_FOR_TESTS,
        input=DECISIONS_INPUT_FOR_TESTS,
        questions=DECISIONS_QUESTIONS_FOR_TESTS,
    )

    opik.flush_tracker()

    assert decision.answers[0].probability == 0.98
    assert len(fake_backend.trace_trees) == 0
