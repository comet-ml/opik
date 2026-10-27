"""Tests against the real TypeSafe AI API.

They run only when ``TYPESAFE_API_KEY`` is set; ``test_typesafe_mocked.py`` always
covers the integration offline. Requests are kept small: the early-access key has
a very limited call budget.
"""

import asyncio
import os

import pytest
from typesafe_sdk import (
    AsyncTypeSafeClient,
    RetryPolicy,
    TypeSafeAuthenticationError,
    TypeSafeClient,
)

import opik
from opik.config import OPIK_PROJECT_DEFAULT_NAME
from opik.integrations.typesafe import track_typesafe

from ...testlib import (
    ANY_BUT_NONE,
    ANY_DICT,
    ANY_STRING,
    SpanModel,
    TraceModel,
    assert_equal,
)
from .constants import (
    EXPECTED_INPUT,
    EXPECTED_TYPESAFE_USAGE_LOGGED_FORMAT,
    QUESTIONS,
    STATE,
)

TYPESAFE_API_KEY = os.environ.get("TYPESAFE_API_KEY", "").strip()

pytestmark = pytest.mark.skipif(
    not TYPESAFE_API_KEY,
    reason="TYPESAFE_API_KEY is not set; the mocked tests cover this integration",
)


def _client() -> TypeSafeClient:
    return TypeSafeClient(
        api_key=TYPESAFE_API_KEY, retry=RetryPolicy(max_retries=0), timeout=30
    )


def _async_client() -> AsyncTypeSafeClient:
    return AsyncTypeSafeClient(
        api_key=TYPESAFE_API_KEY, retry=RetryPolicy(max_retries=0), timeout=30
    )


def test_typesafe_system_one__happyflow(fake_backend):
    client = track_typesafe(_client())

    with client:
        response = client.system_one(state=STATE, questions=QUESTIONS)

    opik.flush_tracker()

    assert response.choices["category"].choice in {"billing", "technical", "other"}

    EXPECTED_TRACE_TREE = TraceModel(
        id=ANY_BUT_NONE,
        name="system_one",
        input=EXPECTED_INPUT,
        output={"answers": ANY_DICT.containing({"category": ANY_DICT})},
        tags=["typesafe"],
        metadata=ANY_DICT,
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
                output={
                    "answers": {
                        "category": ANY_DICT.containing({"type": "choice"}),
                        "is_urgent": ANY_DICT.containing({"type": "noul"}),
                        "frustration": ANY_DICT.containing({"type": "score"}),
                    }
                },
                tags=["typesafe"],
                metadata=ANY_DICT,
                usage=EXPECTED_TYPESAFE_USAGE_LOGGED_FORMAT,
                start_time=ANY_BUT_NONE,
                end_time=ANY_BUT_NONE,
                project_name=OPIK_PROJECT_DEFAULT_NAME,
                spans=[],
                model=ANY_STRING.starting_with("jev"),
                provider="typesafe",
                source="sdk",
            )
        ],
        source="sdk",
    )

    assert len(fake_backend.trace_trees) == 1
    assert_equal(EXPECTED_TRACE_TREE, fake_backend.trace_trees[0])


def test_typesafe_system_one__async__called_in_tracked_function__span_nested_under_track(
    fake_backend,
):
    project_name = "typesafe-integration-test"
    client = track_typesafe(_async_client())

    @opik.track(project_name=project_name)
    async def route_ticket() -> str:
        async with client:
            response = await client.system_one(state=STATE, questions=QUESTIONS)
        return response.choices["category"].choice

    result = asyncio.run(route_ticket())

    opik.flush_tracker()

    EXPECTED_TRACE_TREE = TraceModel(
        id=ANY_BUT_NONE,
        name="route_ticket",
        input={},
        output={"output": result},
        start_time=ANY_BUT_NONE,
        end_time=ANY_BUT_NONE,
        last_updated_at=ANY_BUT_NONE,
        project_name=project_name,
        spans=[
            SpanModel(
                id=ANY_BUT_NONE,
                name="route_ticket",
                input={},
                output={"output": result},
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
                        output={"answers": ANY_DICT},
                        tags=["typesafe"],
                        metadata=ANY_DICT,
                        usage=EXPECTED_TYPESAFE_USAGE_LOGGED_FORMAT,
                        start_time=ANY_BUT_NONE,
                        end_time=ANY_BUT_NONE,
                        project_name=project_name,
                        spans=[],
                        model=ANY_STRING.starting_with("jev"),
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


def test_typesafe_system_one__error_raised__span_and_trace_finished__error_info_logged(
    fake_backend,
):
    client = track_typesafe(
        TypeSafeClient(api_key="invalid-api-key", retry=RetryPolicy(max_retries=0))
    )

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
        metadata=ANY_DICT,
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
                metadata=ANY_DICT,
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
