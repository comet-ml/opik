import opik
from opik.integrations.crewai import track_crewai
from opik.integrations.crewai import opik_tracker
from crewai import Agent, Crew, Process, Task
from ...testlib import (
    ANY,
    ANY_BUT_NONE,
    ANY_STRING,
    ANY_DICT,
    SpanModel,
    TraceModel,
    assert_equal,
)
from . import constants

import pytest


pytestmark = [
    pytest.mark.usefixtures("ensure_openai_configured"),
    pytest.mark.usefixtures("ensure_vertexai_configured"),
    pytest.mark.usefixtures("ensure_aws_bedrock_configured"),
    pytest.mark.usefixtures("ensure_anthropic_configured"),
]


@pytest.mark.parametrize(
    "model, opik_provider",
    [
        ("openai/gpt-4o-mini", "openai"),
        (
            f"{'gemini' if opik_tracker.is_crewai_v1() else 'vertex_ai'}/gemini-2.0-flash",
            "google_vertexai",
        ),
        ("bedrock/us.anthropic.claude-sonnet-4-20250514-v1:0", "bedrock"),
        ("anthropic/claude-sonnet-4-0", "anthropic"),
    ],
)
def test_crewai__sequential_agent__cyclic_reference_inside_one_of_the_tasks__data_is_serialized_correctly(
    fake_backend,
    model,
    opik_provider,
):
    researcher = Agent(
        role="Test Researcher",
        goal="Find basic information",
        backstory="You are a test agent for unit testing.",
        verbose=True,
        llm=model,
    )

    writer = Agent(
        role="Test Writer",
        goal="Write summaries based on research",
        backstory="You are a test writer for unit testing.",
        verbose=True,
        llm=model,
    )

    research_task = Task(
        name="simple_research_task",
        description="Briefly explain what {topic} is in 2-3 sentences.",
        expected_output="A very short explanation of {topic}.",
        agent=researcher,
    )

    # IMPORTANT: context=[research_task] creates a cyclic reference in pydantic
    # which requires special handling during the serialization
    summary_task = Task(
        name="summary_task",
        description="Summarize the research about {topic} in one sentence.",
        expected_output="A one-sentence summary of {topic}.",
        agent=writer,
        context=[research_task],
    )

    crew = Crew(
        agents=[researcher, writer],
        tasks=[research_task, summary_task],
        process=Process.sequential,
        verbose=True,
    )

    track_crewai(project_name=constants.PROJECT_NAME, crew=crew)

    inputs = {"topic": "AI"}
    crew.kickoff(inputs=inputs)
    opik.flush_tracker()

    EXPECTED_TRACE_TREE = TraceModel(
        end_time=ANY_BUT_NONE,
        id=ANY_STRING,
        input=inputs,
        metadata={"created_from": "crewai"},
        name="kickoff",
        output=ANY_DICT,
        project_name=constants.PROJECT_NAME,
        start_time=ANY_BUT_NONE,
        last_updated_at=ANY_BUT_NONE,
        tags=["crewai"],
        spans=[
            SpanModel(
                end_time=ANY_BUT_NONE,
                id=ANY_STRING,
                input=inputs,
                metadata={"created_from": "crewai"},
                name="kickoff",
                output=ANY_DICT,
                project_name=constants.PROJECT_NAME,
                start_time=ANY_BUT_NONE,
                tags=["crewai"],
                type="general",
                spans=[
                    # First task - research task
                    SpanModel(
                        end_time=ANY_BUT_NONE,
                        id=ANY_STRING,
                        input=ANY_DICT,
                        metadata={"created_from": "crewai"},
                        name="Task: simple_research_task",
                        output=ANY_DICT,
                        project_name=constants.PROJECT_NAME,
                        start_time=ANY_BUT_NONE,
                        tags=["crewai"],
                        spans=[
                            SpanModel(
                                end_time=ANY_BUT_NONE,
                                id=ANY_STRING,
                                input=ANY_DICT,
                                metadata={"created_from": "crewai"},
                                name="Test Researcher",
                                output=ANY_DICT,
                                project_name=constants.PROJECT_NAME,
                                start_time=ANY_BUT_NONE,
                                tags=["crewai"],
                                spans=[
                                    SpanModel(
                                        end_time=ANY_BUT_NONE,
                                        id=ANY_STRING,
                                        input=ANY_DICT,
                                        metadata=ANY_DICT,
                                        model=ANY_STRING,
                                        name=ANY_STRING,  # depends on the provider
                                        output=ANY_DICT,
                                        project_name=constants.PROJECT_NAME,
                                        provider=opik_provider,
                                        start_time=ANY_BUT_NONE,
                                        tags=ANY_BUT_NONE,
                                        type="llm",
                                        usage=ANY_DICT.containing(
                                            constants.EXPECTED_SHORT_OPENAI_USAGE_LOGGED_FORMAT
                                        ),
                                        total_cost=ANY,
                                        spans=[],
                                    )
                                ],
                            )
                        ],
                    ),
                    # Second task - summary task
                    SpanModel(
                        end_time=ANY_BUT_NONE,
                        id=ANY_STRING,
                        input=ANY_DICT,
                        metadata={"created_from": "crewai"},
                        name="Task: summary_task",
                        output=ANY_DICT,
                        project_name=constants.PROJECT_NAME,
                        start_time=ANY_BUT_NONE,
                        tags=["crewai"],
                        spans=[
                            SpanModel(
                                end_time=ANY_BUT_NONE,
                                id=ANY_STRING,
                                input=ANY_DICT,
                                metadata={"created_from": "crewai"},
                                name="Test Writer",
                                output=ANY_DICT,
                                project_name=constants.PROJECT_NAME,
                                start_time=ANY_BUT_NONE,
                                tags=["crewai"],
                                spans=[
                                    SpanModel(
                                        end_time=ANY_BUT_NONE,
                                        id=ANY_STRING,
                                        input=ANY_DICT,
                                        metadata=ANY_DICT,
                                        model=ANY_STRING,
                                        name=ANY_STRING,  # depends on the provider
                                        output=ANY_DICT,
                                        project_name=constants.PROJECT_NAME,
                                        provider=opik_provider,
                                        start_time=ANY_BUT_NONE,
                                        tags=ANY_BUT_NONE,
                                        type="llm",
                                        usage=ANY_DICT.containing(
                                            constants.EXPECTED_SHORT_OPENAI_USAGE_LOGGED_FORMAT
                                        ),
                                        total_cost=ANY,
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
