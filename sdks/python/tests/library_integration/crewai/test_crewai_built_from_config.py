import opik
from opik.integrations.crewai import track_crewai
from .crew import LatestAiDevelopmentCrew
from ...testlib import (
    ANY,
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
    project_name = "crewai-test"

    inputs = {"topic": "AI Agents"}
    crew_builder = LatestAiDevelopmentCrew()
    crew = crew_builder.crew()

    track_crewai(project_name=project_name, crew=crew)

    _ = crew.kickoff(inputs=inputs)

    opik.flush_tracker()

    EXPECTED_TRACE_TREE = TraceModel(
        end_time=ANY_BUT_NONE,
        id=ANY_STRING,
        input={"topic": "AI Agents"},
        metadata={"created_from": "crewai"},
        name="kickoff",
        output=ANY_DICT,
        project_name=project_name,
        start_time=ANY_BUT_NONE,
        last_updated_at=ANY_BUT_NONE,
        tags=["crewai"],
        spans=[
            SpanModel(
                end_time=ANY_BUT_NONE,
                id=ANY_STRING,
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
                        id=ANY_STRING,
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
                                id=ANY_STRING,
                                input=ANY_DICT,
                                metadata={"created_from": "crewai"},
                                name="AI Agents Senior Data Researcher",
                                output=ANY_DICT,
                                project_name=project_name,
                                start_time=ANY_BUT_NONE,
                                tags=["crewai"],
                                type="general",
                                spans=ANY,  # Flexible - LLM spans may or may not be present depending on initialization timing
                            )
                        ],
                    ),
                    SpanModel(
                        end_time=ANY_BUT_NONE,
                        id=ANY_STRING,
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
                                id=ANY_STRING,
                                input=ANY_DICT,
                                metadata={"created_from": "crewai"},
                                name="AI Agents Reporting Analyst",
                                output=ANY_DICT,
                                project_name=project_name,
                                start_time=ANY_BUT_NONE,
                                tags=["crewai"],
                                type="general",
                                spans=ANY,  # Flexible - LLM spans may or may not be present depending on initialization timing
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
