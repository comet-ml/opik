from opik.api_objects.opik_client import get_client_cached
from opik.integrations.crewai import track_crewai
from .crew import LatestAiDevelopmentCrew
from ...testlib import (
    ANY_BUT_NONE,
    ANY_DICT,
    ANY_STRING,
    SpanModel,
    TraceModel,
    assert_equal,
)


def test_crewai__happyflow(
    fake_backend,
):
    project_name = "crewai-integration-test"

    track_crewai(project_name=project_name)

    inputs = {"topic": "AI Agents"}
    c = LatestAiDevelopmentCrew()
    c = c.crew()
    _ = c.kickoff(inputs=inputs)

    opik_client = get_client_cached()
    opik_client.flush()

    EXPECTED_TRACE_TREE = TraceModel(
        end_time=ANY_BUT_NONE,
        id=ANY_STRING(),
        input={"topic": "AI Agents"},
        metadata={"created_from": "crewai"},
        name="kickoff",
        output=ANY_DICT,
        project_name=project_name,
        start_time=ANY_BUT_NONE,
        tags=["crewai"],
        spans=[
            SpanModel(
                end_time=ANY_BUT_NONE,
                id=ANY_STRING(),
                input=ANY_DICT,
                metadata={"created_from": "crewai"},
                name="kickoff",
                output=ANY_DICT,
                project_name=project_name,
                start_time=ANY_BUT_NONE,
                tags=["crewai"],
                type="general",
                spans=[
                    SpanModel(
                        end_time=ANY_BUT_NONE,
                        id=ANY_STRING(),
                        input=ANY_DICT,
                        metadata={"created_from": "crewai"},
                        name="Task: research_task",
                        output=ANY_DICT,
                        project_name=project_name,
                        start_time=ANY_BUT_NONE,
                        tags=["crewai"],
                        spans=[
                            SpanModel(
                                end_time=ANY_BUT_NONE,
                                id=ANY_STRING(),
                                input=ANY_DICT,
                                metadata={"created_from": "crewai"},
                                name="AI Agents Senior Data Researcher",
                                output=ANY_DICT,
                                project_name=project_name,
                                start_time=ANY_BUT_NONE,
                                tags=["crewai"],
                                type="general",
                                spans=[
                                    SpanModel(
                                        end_time=ANY_BUT_NONE,
                                        id=ANY_STRING(),
                                        input=ANY_DICT,
                                        metadata={
                                            "created_from": "crewai",
                                            "usage": ANY_DICT,
                                        },
                                        model=ANY_STRING(startswith="gpt-4o-mini"),
                                        name="llm call",
                                        output=ANY_DICT,
                                        project_name=project_name,
                                        provider="openai",
                                        start_time=ANY_BUT_NONE,
                                        tags=["crewai"],
                                        type="llm",
                                        usage={
                                            "prompt_tokens": ANY_BUT_NONE,
                                            "completion_tokens": ANY_BUT_NONE,
                                            "total_tokens": ANY_BUT_NONE,
                                        },
                                        spans=[],
                                    )
                                ],
                            )
                        ],
                    ),
                    SpanModel(
                        end_time=ANY_BUT_NONE,
                        id=ANY_STRING(),
                        input=ANY_DICT,
                        metadata={"created_from": "crewai"},
                        name="Task: reporting_task",
                        output=ANY_DICT,
                        project_name=project_name,
                        start_time=ANY_BUT_NONE,
                        tags=["crewai"],
                        spans=[
                            SpanModel(
                                end_time=ANY_BUT_NONE,
                                id=ANY_STRING(),
                                input=ANY_DICT,
                                metadata={"created_from": "crewai"},
                                name="AI Agents Reporting Analyst",
                                output=ANY_DICT,
                                project_name=project_name,
                                start_time=ANY_BUT_NONE,
                                tags=["crewai"],
                                type="general",
                                spans=[
                                    SpanModel(
                                        end_time=ANY_BUT_NONE,
                                        id=ANY_STRING(),
                                        input=ANY_DICT,
                                        metadata={
                                            "created_from": "crewai",
                                            "usage": ANY_DICT,
                                        },
                                        model=ANY_STRING(startswith="gpt-4o-mini"),
                                        name="llm call",
                                        output=ANY_DICT,
                                        project_name=project_name,
                                        provider="openai",
                                        start_time=ANY_BUT_NONE,
                                        tags=["crewai"],
                                        type="llm",
                                        usage={
                                            "prompt_tokens": ANY_BUT_NONE,
                                            "completion_tokens": ANY_BUT_NONE,
                                            "total_tokens": ANY_BUT_NONE,
                                        },
                                        spans=[],
                                    )
                                ],
                            )
                        ],
                    ),
                ],
            ),
        ],
    )

    assert len(fake_backend.trace_trees) == 1
    assert len(fake_backend.span_trees) == 1

    assert_equal(EXPECTED_TRACE_TREE, fake_backend.trace_trees[0])
