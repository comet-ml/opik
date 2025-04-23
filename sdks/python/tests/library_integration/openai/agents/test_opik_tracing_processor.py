from agents import Agent, Runner
from agents import set_trace_processors

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
        name="Agent workflow",
        project_name=project_name,
        end_time=ANY_BUT_NONE,
        spans=[
            SpanModel(
                id=ANY_BUT_NONE,
                start_time=ANY_BUT_NONE,
                name="Assistant",
                output={"output": "str"},
                metadata=ANY_DICT,
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
