import pytest
from agents import Agent, Runner, set_trace_processors, function_tool

import opik
from opik.integrations.openai.agents import OpikTracingProcessor
from ..constants import MODEL_FOR_TESTS, EXPECTED_OPENAI_USAGE_LOGGED_FORMAT
from ....testlib import (
    ANY_BUT_NONE,
    ANY_LIST,
    ANY_DICT,
    ANY_STRING,
    SpanModel,
    TraceModel,
    assert_equal,
)


def test_opik_tracing_processor__happy_flow(fake_backend):
    input_message = "Write a haiku about recursion in programming."
    project_name = "opik-test-openai-agents"

    set_trace_processors(processors=[OpikTracingProcessor(project_name)])

    agent = Agent(
        name="Assistant",
        instructions="You are a helpful assistant",
        model=MODEL_FOR_TESTS,
    )

    Runner.run_sync(agent, input_message)

    opik.flush_tracker()

    EXPECTED_TRACE_TREE = TraceModel(
        id=ANY_BUT_NONE,
        start_time=ANY_BUT_NONE,
        input=ANY_DICT,
        output=ANY_DICT,
        name="Agent workflow",
        project_name=project_name,
        end_time=ANY_BUT_NONE,
        spans=[
            SpanModel(
                id=ANY_BUT_NONE,
                start_time=ANY_BUT_NONE,
                name="Assistant",
                metadata=ANY_DICT,
                output={"output": "str"},
                type="general",
                end_time=ANY_BUT_NONE,
                project_name=project_name,
                spans=[
                    SpanModel(
                        id=ANY_BUT_NONE,
                        start_time=ANY_BUT_NONE,
                        name="Response",
                        input={
                            "input": [
                                {
                                    "content": "Write a haiku about recursion in programming.",
                                    "role": "user",
                                }
                            ]
                        },
                        output={"output": ANY_LIST},
                        metadata=ANY_DICT,
                        type="llm",
                        usage=EXPECTED_OPENAI_USAGE_LOGGED_FORMAT,
                        end_time=ANY_BUT_NONE,
                        project_name=project_name,
                        model=ANY_STRING(startswith=MODEL_FOR_TESTS),
                        provider=ANY_BUT_NONE,
                    )
                ],
                provider=ANY_BUT_NONE,
            )
        ],
    )

    assert len(fake_backend.trace_trees) == 1
    trace_tree = fake_backend.trace_trees[0]

    assert_equal(expected=EXPECTED_TRACE_TREE, actual=trace_tree)


@pytest.mark.asyncio
async def test_opik_tracing_processor__handsoff(fake_backend):
    input_message = "Hola, ¿cómo estás?"
    project_name = "opik-test-openai-agents-handsoff"

    set_trace_processors(processors=[OpikTracingProcessor(project_name)])

    spanish_agent = Agent(
        name="Spanish agent",
        instructions="You only speak Spanish.",
        model=MODEL_FOR_TESTS,
    )

    english_agent = Agent(
        name="English agent",
        instructions="You only speak English",
        model=MODEL_FOR_TESTS,
    )

    triage_agent = Agent(
        name="Triage agent",
        instructions="Handoff to the appropriate agent based on the language of the request.",
        handoffs=[spanish_agent, english_agent],
        model=MODEL_FOR_TESTS,
    )

    _ = await Runner.run(triage_agent, input=input_message)

    opik.flush_tracker()

    EXPECTED_TRACE_TREE = TraceModel(
        id=ANY_BUT_NONE,
        start_time=ANY_BUT_NONE,
        name="Agent workflow",
        project_name=project_name,
        input=ANY_DICT,
        output=ANY_DICT,
        end_time=ANY_BUT_NONE,
        spans=[
            SpanModel(
                id=ANY_BUT_NONE,
                start_time=ANY_BUT_NONE,
                name="Triage agent",
                metadata=ANY_DICT,
                output={"output": "str"},
                type="general",
                end_time=ANY_BUT_NONE,
                project_name=project_name,
                spans=[
                    SpanModel(
                        id=ANY_BUT_NONE,
                        start_time=ANY_BUT_NONE,
                        name="Response",
                        input={"input": [{"content": input_message, "role": "user"}]},
                        output={"output": ANY_BUT_NONE},
                        metadata=ANY_DICT,
                        type="llm",
                        usage=EXPECTED_OPENAI_USAGE_LOGGED_FORMAT,
                        end_time=ANY_BUT_NONE,
                        project_name=project_name,
                        model=ANY_STRING(startswith=MODEL_FOR_TESTS),
                        provider=ANY_BUT_NONE,
                    ),
                    SpanModel(
                        id=ANY_BUT_NONE,
                        start_time=ANY_BUT_NONE,
                        name="Handoff",
                        metadata={
                            "type": "handoff",
                            "from_agent": "Triage agent",
                            "to_agent": "Spanish agent",
                        },
                        type="general",
                        end_time=ANY_BUT_NONE,
                        project_name=project_name,
                        provider=ANY_BUT_NONE,
                    ),
                ],
                provider=ANY_BUT_NONE,
            ),
            SpanModel(
                id=ANY_BUT_NONE,
                start_time=ANY_BUT_NONE,
                name="Spanish agent",
                metadata={
                    "type": "agent",
                    "name": "Spanish agent",
                    "handoffs": [],
                    "tools": [],
                    "output_type": "str",
                },
                output={"output": "str"},
                type="general",
                end_time=ANY_BUT_NONE,
                project_name=project_name,
                spans=[
                    SpanModel(
                        id=ANY_BUT_NONE,
                        start_time=ANY_BUT_NONE,
                        name="Response",
                        input={"input": ANY_LIST},
                        output={"output": ANY_BUT_NONE},
                        metadata=ANY_DICT,
                        type="llm",
                        usage=EXPECTED_OPENAI_USAGE_LOGGED_FORMAT,
                        end_time=ANY_BUT_NONE,
                        project_name=project_name,
                        model=ANY_STRING(startswith=MODEL_FOR_TESTS),
                        provider=ANY_BUT_NONE,
                    )
                ],
                provider=ANY_BUT_NONE,
            ),
        ],
    )

    assert len(fake_backend.trace_trees) == 1
    trace_tree = fake_backend.trace_trees[0]

    assert_equal(expected=EXPECTED_TRACE_TREE, actual=trace_tree)


@pytest.mark.asyncio
async def test_opik_tracing_processor__functions(fake_backend):
    @function_tool
    def get_weather(city: str) -> str:
        return f"The weather in {city} is sunny."

    input_message = "What's the weather in Tokyo?"
    project_name = "opik-test-openai-agents-function"

    set_trace_processors(processors=[OpikTracingProcessor(project_name)])

    agent = Agent(
        name="Hello world",
        instructions="You are a helpful agent.",
        tools=[get_weather],
        model=MODEL_FOR_TESTS,
    )

    _ = await Runner.run(agent, input=input_message)

    opik.flush_tracker()

    EXPECTED_TRACE_TREE = TraceModel(
        id=ANY_BUT_NONE,
        start_time=ANY_BUT_NONE,
        input=ANY_DICT,
        output=ANY_DICT,
        name="Agent workflow",
        project_name=project_name,
        end_time=ANY_BUT_NONE,
        spans=[
            SpanModel(
                id=ANY_BUT_NONE,
                start_time=ANY_BUT_NONE,
                name="Hello world",
                metadata={
                    "type": "agent",
                    "name": "Hello world",
                    "handoffs": [],
                    "tools": ["get_weather"],
                    "output_type": "str",
                },
                output={"output": "str"},
                type="general",
                end_time=ANY_BUT_NONE,
                project_name=project_name,
                spans=[
                    SpanModel(
                        id=ANY_BUT_NONE,
                        start_time=ANY_BUT_NONE,
                        name="Response",
                        input={"input": [{"content": input_message, "role": "user"}]},
                        output={"output": ANY_BUT_NONE},
                        metadata=ANY_DICT,
                        type="llm",
                        usage=EXPECTED_OPENAI_USAGE_LOGGED_FORMAT,
                        end_time=ANY_BUT_NONE,
                        project_name=project_name,
                        model=ANY_STRING(startswith=MODEL_FOR_TESTS),
                        provider=ANY_BUT_NONE,
                    ),
                    SpanModel(
                        id=ANY_BUT_NONE,
                        start_time=ANY_BUT_NONE,
                        name="get_weather",
                        input={"input": '{"city":"Tokyo"}'},
                        output={"output": "The weather in Tokyo is sunny."},
                        metadata={
                            "type": "function",
                            "name": "get_weather",
                            "mcp_data": None,
                        },
                        type="tool",
                        end_time=ANY_BUT_NONE,
                        project_name=project_name,
                        provider=ANY_BUT_NONE,
                    ),
                    SpanModel(
                        id=ANY_BUT_NONE,
                        start_time=ANY_BUT_NONE,
                        name="Response",
                        input={"input": ANY_BUT_NONE},
                        output={"output": ANY_BUT_NONE},
                        metadata=ANY_DICT,
                        type="llm",
                        usage=EXPECTED_OPENAI_USAGE_LOGGED_FORMAT,
                        end_time=ANY_BUT_NONE,
                        project_name=project_name,
                        model=ANY_STRING(startswith=MODEL_FOR_TESTS),
                        provider=ANY_BUT_NONE,
                    ),
                ],
                provider=ANY_BUT_NONE,
            )
        ],
    )

    assert len(fake_backend.trace_trees) == 1
    trace_tree = fake_backend.trace_trees[0]

    assert_equal(expected=EXPECTED_TRACE_TREE, actual=trace_tree)
