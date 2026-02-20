from langchain.agents import create_agent
from langchain_core.language_models.fake_chat_models import GenericFakeChatModel
from langchain_core.messages import AIMessage
from langgraph_supervisor import create_supervisor

from opik.integrations.langchain import (
    OpikTracer,
    LANGGRAPH_PARENT_COMMAND_METADATA_KEY,
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
        messages=iter([AIMessage(content="The weather in London is sunny and 22°C.")])
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

    # ── Assertions ────────────────────────────────────────────────────────────
    assert len(fake_backend.trace_trees) == 1
    assert len(opik_tracer.created_traces()) == 1

    trace_tree = fake_backend.trace_trees[0]

    def collect_all_spans(spans):
        result = []
        for s in spans:
            result.append(s)
            result.extend(collect_all_spans(s.spans))
        return result

    all_spans = collect_all_spans(trace_tree.spans)

    # 1. No span should have error_info — ParentCommand is NOT a real error.
    for s in all_spans:
        assert s.error_info is None, (
            f"Span '{s.name}' has unexpected error_info: {s.error_info}. "
            f"langgraph.errors.ParentCommand must be treated as control flow, "
            f"not an error."
        )

    # 2. At least one span must carry the ParentCommand control-flow marker.
    parent_command_spans = [
        s
        for s in all_spans
        if s.metadata and s.metadata.get(LANGGRAPH_PARENT_COMMAND_METADATA_KEY) is True
    ]
    assert len(parent_command_spans) >= 1, (
        f"Expected at least one span with "
        f"metadata['{LANGGRAPH_PARENT_COMMAND_METADATA_KEY}'] = True, "
        f"but none found. Spans present: {[s.name for s in all_spans]}"
    )

    # 3. The supervisor span specifically must have no error_info.
    supervisor_spans = [s for s in all_spans if s.name == "supervisor"]
    assert len(supervisor_spans) >= 1, (
        "Expected at least one span named 'supervisor' in the trace tree."
    )
    for s in supervisor_spans:
        assert s.error_info is None, (
            f"The 'supervisor' span has error_info set: {s.error_info}. "
            f"ParentCommand raised inside the supervisor subgraph must not "
            f"surface as an error."
        )
