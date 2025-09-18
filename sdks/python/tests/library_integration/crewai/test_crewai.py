import opik
from opik.integrations.crewai import track_crewai
from crewai import Agent, Crew, Process, Task
from ...testlib import (
    ANY_BUT_NONE,
    ANY_STRING,
    ANY_DICT,
    SpanModel,
    TraceModel,
    assert_equal,
)
from . import constants


def test_crewai__sequential_agent__cyclic_reference_inside_one_of_the_tasks__data_is_serialized_correctly(
    fake_backend,
):
    track_crewai(project_name=constants.PROJECT_NAME)

    researcher = Agent(
        role="Test Researcher",
        goal="Find basic information",
        backstory="You are a test agent for unit testing.",
        verbose=True,
        llm=constants.MODEL_NAME_SHORT,
    )

    writer = Agent(
        role="Test Writer",
        goal="Write summaries based on research",
        backstory="You are a test writer for unit testing.",
        verbose=True,
        llm=constants.MODEL_NAME_SHORT,
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
                                        metadata=ANY_DICT.containing(
                                            {
                                                "created_from": "litellm",
                                            }
                                        ),
                                        model=ANY_STRING,
                                        name="completion",
                                        output=ANY_DICT,
                                        project_name=constants.PROJECT_NAME,
                                        provider="openai",
                                        start_time=ANY_BUT_NONE,
                                        tags=["litellm"],
                                        type="llm",
                                        usage=constants.EXPECTED_OPENAI_USAGE_LOGGED_FORMAT,
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
                                        metadata=ANY_DICT.containing(
                                            {
                                                "created_from": "litellm",
                                            }
                                        ),
                                        model=ANY_STRING,
                                        name="completion",
                                        output=ANY_DICT,
                                        project_name=constants.PROJECT_NAME,
                                        provider="openai",
                                        start_time=ANY_BUT_NONE,
                                        tags=["litellm"],
                                        type="llm",
                                        usage=constants.EXPECTED_OPENAI_USAGE_LOGGED_FORMAT,
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
