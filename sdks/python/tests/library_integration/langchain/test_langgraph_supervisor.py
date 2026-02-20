import importlib.metadata

import pytest

from opik import semantic_version
from opik.integrations.langchain import (
    OpikTracer,
    LANGGRAPH_PARENT_COMMAND_METADATA_KEY,
)

from ...testlib import (
    ANY_BUT_NONE,
    ANY_DICT,
    ANY_LIST,
    SpanModel,
    TraceModel,
    assert_equal,
)

LANGCHAIN_VERSION_OLDER_THAN_1_0_0 = (
    semantic_version.SemanticVersion.parse(importlib.metadata.version("langchain"))
    <= "1.0.0"
)


@pytest.mark.skipif(
    LANGCHAIN_VERSION_OLDER_THAN_1_0_0,
    reason="Only applies to langchain > 1.0.0",
)
def test_langgraph__supervisor_multi_agent__parent_command_not_logged_as_error(
    fake_backend,
):
    """Test that LangGraph's ParentCommand control flow exception is not logged as an error.

    In a supervisor/multi-agent pattern built with langgraph-supervisor, the supervisor
    uses handoff tools (e.g., transfer_to_weather_agent) to delegate tasks to subagents.
    Internally, LangGraph raises langgraph.errors.ParentCommand when a subgraph node
    routes execution to a node in the parent graph. This is a normal control flow, not an
    error.

    This test verifies that:
      1. No span has error_info set — ParentCommand must not be treated as an error.
      2. Spans where ParentCommand routing occurred carry
         metadata["_langgraph_parent_command"] = True.
      3. The supervisor span itself has no error_info.
    """

    from langchain.agents import create_agent
    from langchain_core.language_models.fake_chat_models import GenericFakeChatModel
    from langchain_core.messages import AIMessage
    from langgraph_supervisor import create_supervisor

    # GenericFakeChatModel does not implement bind_tools, which create_supervisor
    # calls internally. This thin subclass passes it through so the fake model
    # can still deliver its pre-configured messages unchanged.
    class _FakeChatModelWithTools(GenericFakeChatModel):
        def bind_tools(self, tools, *, tool_choice=None, **kwargs):
            return self

    # ── Weather subagent ─────────────────────────────────────────────────────
    # Uses a fake LLM that returns a single plain-text response (no tool calls),
    # signaling that the agent has finished its work.
    weather_agent_llm = _FakeChatModelWithTools(
        messages=iter([AIMessage(content="The weather in Kyiv is sunny and 22°C.")])
    )
    weather_agent = create_agent(
        model=weather_agent_llm,
        tools=[],
        name="weather_agent",
        system_prompt="You are a weather expert. Answer weather questions directly.",
    )

    # ── Supervisor ────────────────────────────────────────────────────────────
    # Call 1: returns a tool_call to transfer_to_weather_agent — this is the
    #         step that internally raises ParentCommand inside the supervisor
    #         subgraph to route execution to the weather_agent node.
    # Call 2: after the weather_agent responds, the supervisor is invoked again
    #         to produce the final answer (no tool call → graph terminates).
    supervisor_llm = _FakeChatModelWithTools(
        messages=iter(
            [
                AIMessage(
                    content="",
                    tool_calls=[
                        {
                            "name": "transfer_to_weather_agent",
                            "args": {},
                            "id": "call_test_handoff_001",
                            "type": "tool_call",
                        }
                    ],
                ),
                AIMessage(content="The weather in Kyiv is sunny and 22°C."),
            ]
        )
    )
    supervisor_builder = create_supervisor(
        agents=[weather_agent],
        model=supervisor_llm,
        prompt="You are a supervisor. Route weather questions to weather_agent.",
        supervisor_name="supervisor",
    )
    graph = supervisor_builder.compile()

    # ── Run with OpikTracer ───────────────────────────────────────────────────
    opik_tracer = OpikTracer(tags=["parent-command-test"])
    graph.invoke(
        {"messages": [{"role": "user", "content": "What's the weather in Kyiv?"}]},
        config={"callbacks": [opik_tracer]},
    )
    opik_tracer.flush()

    # ── Expected trace tree ───────────────────────────────────────────────────
    # The supervisor/multi-agent graph produces three top-level spans:
    #
    #  supervisor  (1st call — handoff tool fires, raising ParentCommand internally)
    #    supervisor
    #      agent → call_model, RunnableSequence, should_continue
    #      tools  (pc=True) → transfer_to_weather_agent
    #  weather_agent  (routed to by ParentCommand)
    #    call_agent
    #    weather_agent → model → _FakeChatModelWithTools
    #  supervisor  (2nd call — returns the final answer, no tool call)
    #    supervisor → agent → call_model, RunnableSequence, should_continue
    #
    # Key assertions encoded directly in the model:
    #   • error_info=None  on every modelled span  → ParentCommand is NOT an error
    #   • metadata containing LANGGRAPH_PARENT_COMMAND_METADATA_KEY: True
    #     on the first supervisor span              → control-flow marker is present
    #   • spans=ANY_LIST for deep children         → nested structure not under test
    EXPECTED_TRACE = TraceModel(
        id=ANY_BUT_NONE,
        name="LangGraph",
        input=ANY_DICT,
        output=ANY_DICT,
        metadata=ANY_DICT.containing({"created_from": "langchain"}),
        tags=["parent-command-test"],
        start_time=ANY_BUT_NONE,
        end_time=ANY_BUT_NONE,
        last_updated_at=ANY_BUT_NONE,
        error_info=None,
        spans=[
            # First supervisor invocation: the handoff tool fires, raising
            # ParentCommand internally. This must NOT produce error_info; instead
            # it carries the _langgraph_parent_command metadata marker.
            # output=None because the span ends via control flow, not a return value.
            SpanModel(
                id=ANY_BUT_NONE,
                name="supervisor",
                input=ANY_DICT,
                output=None,
                metadata=ANY_DICT.containing(
                    {
                        "created_from": "langchain",
                        LANGGRAPH_PARENT_COMMAND_METADATA_KEY: True,
                    }
                ),
                start_time=ANY_BUT_NONE,
                end_time=ANY_BUT_NONE,
                error_info=None,
                spans=ANY_LIST,
            ),
            # weather_agent executes after the ParentCommand routing completes.
            SpanModel(
                id=ANY_BUT_NONE,
                name="weather_agent",
                input=ANY_DICT,
                output=ANY_DICT,
                metadata=ANY_DICT.containing({"created_from": "langchain"}),
                start_time=ANY_BUT_NONE,
                end_time=ANY_BUT_NONE,
                error_info=None,
                spans=ANY_LIST,
            ),
            # Second supervisor invocation: returns the final answer with no
            # tool calls, so no ParentCommand is raised here.
            SpanModel(
                id=ANY_BUT_NONE,
                name="supervisor",
                input=ANY_DICT,
                output=ANY_DICT,
                metadata=ANY_DICT.containing({"created_from": "langchain"}),
                start_time=ANY_BUT_NONE,
                end_time=ANY_BUT_NONE,
                error_info=None,
                spans=ANY_LIST,
            ),
        ],
    )

    assert len(fake_backend.trace_trees) == 1
    assert len(opik_tracer.created_traces()) == 1
    assert_equal(EXPECTED_TRACE, fake_backend.trace_trees[0])
