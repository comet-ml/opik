"""Ensure Atomic Agents tool executions create child tool spans."""

from __future__ import annotations

import sys

from opik.integrations.atomic_agents import track_atomic_agents

import opik


def test_tool_span_created(fake_backend):
    base_agent_module = sys.modules["atomic_agents.agents.base_agent"]

    original_method = base_agent_module.BaseAgent.get_response

    def _enhanced_get_response(self, messages):
        result = original_method(self, messages)

        BaseTool = sys.modules["atomic_agents.lib.base.base_tool"].BaseTool
        tool = BaseTool("MyTool")
        tool.run("tool payload")
        return result

    base_agent_module.BaseAgent.get_response = _enhanced_get_response

    try:
        track_atomic_agents(project_name="tool-span")

        BaseAgent = sys.modules["atomic_agents.agents.base_agent"].BaseAgent
        agent = BaseAgent()
        agent.run("test")

        opik.flush_tracker()

        assert len(fake_backend.trace_trees) == 1
        trace_tree = fake_backend.trace_trees[0]

        tool_spans = [span for span in trace_tree.spans if span.type == "tool"]
        assert (
            len(tool_spans) > 0
        ), f"No tool spans found. Available spans: {[(s.name, s.type) for s in trace_tree.spans]}"
        assert tool_spans[0].name == "MyTool"
    finally:
        base_agent_module.BaseAgent.get_response = original_method
