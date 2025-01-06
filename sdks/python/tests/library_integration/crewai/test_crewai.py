from typing import Union

import pytest

import opik
from opik import context_storage
from opik.api_objects import opik_client, span, trace
from opik.api_objects.opik_client import get_client_cached
from opik.config import OPIK_PROJECT_DEFAULT_NAME

from .crew import LatestAiDevelopmentCrew
from opik.integrations.crewai import track_crewai
from ...testlib import (
    ANY_BUT_NONE,
    ANY_DICT,
    ANY_STRING,
    SpanModel,
    TraceModel,
    assert_equal,
)



import litellm
litellm.set_verbose=True


def test_crewai__happyflow(
    fake_backend,
):
    project_name = "crewai-integration-test"

    track_crewai(project_name=project_name)

    inputs = {
        'topic': 'AI Agents'
    }
    c = LatestAiDevelopmentCrew()
    c = c.crew()

    c = c.kickoff(inputs=inputs)

    print(c)

    opik_client = get_client_cached()
    opik_client.flush()

    EXPECTED_TRACE_TREE = TraceModel(
        id=ANY_STRING(),
        name="ChainOfThought",
        input={"args": (), "kwargs": {"question": "What is the meaning of life?"}},
        output=None,
        metadata={"created_from": "dspy"},
        start_time=ANY_BUT_NONE,
        end_time=ANY_BUT_NONE,
        project_name=project_name,
        spans=[
            SpanModel(
                id=ANY_STRING(),
                type="llm",
                name="LM",
                provider="openai",
                model="gpt-4o-mini",
                input=ANY_DICT,
                output=ANY_DICT,
                metadata=None,
                start_time=ANY_BUT_NONE,
                end_time=ANY_BUT_NONE,
                project_name=project_name,
                spans=[],
            ),
            SpanModel(
                id=ANY_STRING(),
                type="llm",
                name="Predict",
                provider=None,
                model=None,
                input=ANY_DICT,
                output=ANY_DICT,
                metadata=None,
                start_time=ANY_BUT_NONE,
                end_time=ANY_BUT_NONE,
                project_name=project_name,
                spans=[],
            ),
        ],
    )

    # assert len(fake_backend.trace_trees) == 1
    # assert len(fake_backend.span_trees) == 2
    #
    # assert_equal(EXPECTED_TRACE_TREE, fake_backend.trace_trees[0])



