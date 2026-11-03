import openai
import pytest

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
    assert_equal,
)
from .constants import (
    DECISIONS_INPUT_FOR_TESTS,
    DECISIONS_MODEL_FOR_TESTS,
    DECISIONS_QUESTIONS_FOR_TESTS,
    EXPECTED_OPENAI_USAGE_LOGGED_FORMAT,
)


@pytest.fixture(autouse=True)
def check_openai_configured(ensure_openai_configured):
    pass


def test_openai_decisions_create__happyflow(fake_backend):
    client = track_openai(openai.OpenAI())

    decision = client.decisions.create(
        model=DECISIONS_MODEL_FOR_TESTS,
        input=DECISIONS_INPUT_FOR_TESTS,
        questions=DECISIONS_QUESTIONS_FOR_TESTS,
    )

    opik.flush_tracker()

    assert [answer.name for answer in decision.answers] == [
        question["name"] for question in DECISIONS_QUESTIONS_FOR_TESTS
    ]

    EXPECTED_INPUT = {
        "input": DECISIONS_INPUT_FOR_TESTS,
        "questions": DECISIONS_QUESTIONS_FOR_TESTS,
    }
    EXPECTED_OUTPUT = {"answers": ANY_LIST}
    EXPECTED_METADATA = ANY_DICT.containing(
        {"created_from": "openai", "type": "openai_decisions"}
    )

    EXPECTED_TRACE_TREE = TraceModel(
        id=ANY_BUT_NONE,
        name="decisions_create",
        input=EXPECTED_INPUT,
        output=EXPECTED_OUTPUT,
        tags=["openai"],
        metadata=EXPECTED_METADATA,
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
                output=EXPECTED_OUTPUT,
                tags=["openai"],
                metadata=EXPECTED_METADATA,
                usage=ANY_DICT.containing(EXPECTED_OPENAI_USAGE_LOGGED_FORMAT),
                start_time=ANY_BUT_NONE,
                end_time=ANY_BUT_NONE,
                project_name=OPIK_PROJECT_DEFAULT_NAME,
                spans=[],
                model=ANY_STRING.starting_with(DECISIONS_MODEL_FOR_TESTS),
                provider="openai",
                source="sdk",
            )
        ],
        source="sdk",
    )

    assert len(fake_backend.trace_trees) == 1
    assert_equal(EXPECTED_TRACE_TREE, fake_backend.trace_trees[0])
