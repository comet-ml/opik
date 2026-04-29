import pytest
from crewai import Agent, Crew, LLM, Process, Task

import opik
from opik.integrations.crewai import opik_tracker, track_crewai

from . import constants
from ... import llm_constants
from ...testlib import (
    ANY,
    ANY_BUT_NONE,
    ANY_STRING,
    ANY_DICT,
    SpanModel,
    TraceModel,
    assert_equal,
)

pytestmark = [
    pytest.mark.usefixtures("ensure_openai_configured"),
    pytest.mark.usefixtures("ensure_vertexai_configured"),
    pytest.mark.usefixtures("ensure_aws_bedrock_configured"),
    pytest.mark.usefixtures("ensure_anthropic_configured"),
]


# CrewAI v0 still runs against gpt-4o-mini: its pinned litellm==1.74.9
# reports `stop` as supported for gpt-5-nano and then CrewAI's ReAct loop
# injects stop tokens the OpenAI API rejects. gpt-4o-mini dodges that.
# v1 standardises on gpt-5-nano like the rest of the suite.
_OPENAI_MODEL = (
    llm_constants.LITELLM_OPENAI_GPT_NANO
    if opik_tracker.is_crewai_v1()
    else llm_constants.LITELLM_OPENAI_GPT_4O_MINI
)
# v0 routes Gemini through litellm's vertex_ai provider prefix; v1's genai
# integration infers it from GOOGLE_GENAI_USE_VERTEXAI.
_GEMINI_MODEL = (
    f"gemini/{llm_constants.GEMINI_FLASH}"
    if opik_tracker.is_crewai_v1()
    else f"vertex_ai/{llm_constants.GEMINI_FLASH}"
)


@pytest.mark.parametrize(
    "model, opik_provider",
    [
        (_OPENAI_MODEL, "openai"),
        (_GEMINI_MODEL, "google_vertexai"),
        (f"bedrock/{llm_constants.BEDROCK_CLAUDE_SONNET}", "bedrock"),
        (f"anthropic/{llm_constants.ANTHROPIC_CLAUDE_SONNET}", "anthropic"),
    ],
)
def test_crewai__sequential_agent__cyclic_reference_inside_one_of_the_tasks__data_is_serialized_correctly(
    fake_backend,
    model,
    opik_provider,
):
    # reasoning_effort="minimal" only applies on v1 where the OpenAI model
    # is gpt-5-nano. On v0 (gpt-4o-mini) it's rejected by the OpenAI API.
    llm_kwargs = (
        {"reasoning_effort": llm_constants.OPENAI_REASONING_EFFORT}
        if model == llm_constants.LITELLM_OPENAI_GPT_NANO
        else {}
    )
    agent_llm = LLM(model=model, **llm_kwargs)

    researcher = Agent(
        role="Test Researcher",
        goal="Find basic information",
        backstory="You are a test agent for unit testing.",
        verbose=True,
        llm=agent_llm,
    )

    writer = Agent(
        role="Test Writer",
        goal="Write summaries based on research",
        backstory="You are a test writer for unit testing.",
        verbose=True,
        llm=agent_llm,
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
                                        source="sdk",
                                    )
                                ],
                                source="sdk",
                            )
                        ],
                        source="sdk",
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
                                        source="sdk",
                                    )
                                ],
                                source="sdk",
                            )
                        ],
                        source="sdk",
                    ),
                ],
                source="sdk",
            ),
        ],
        source="sdk",
    )

    assert len(fake_backend.trace_trees) == 1
    assert len(fake_backend.span_trees) == 1

    assert_equal(EXPECTED_TRACE_TREE, fake_backend.trace_trees[0])
