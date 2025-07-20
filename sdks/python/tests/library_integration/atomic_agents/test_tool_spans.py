"""Ensure Atomic Agents tool executions create child tool spans."""

from __future__ import annotations

import sys

from opik.integrations.atomic_agents import track_atomic_agents

import opik


def test_tool_span_created(fake_backend):
    # Modify the mock agent to call a tool during its execution

    base_agent_module = sys.modules["atomic_agents.agents.base_agent"]

    # Store the original get_and_handle_response
    original_method = base_agent_module.BaseChatAgent._get_and_handle_response

    def _enhanced_get_response(self, messages):
        # Call the original method first
        result = original_method(self, messages)
        # Now call a tool
        BaseTool = sys.modules["atomic_agents.tools.base_tool"].BaseTool
        tool = BaseTool("MyTool")
        tool.run("tool payload")
        return result

    # Patch the method before tracking is enabled
    base_agent_module.BaseChatAgent._get_and_handle_response = _enhanced_get_response

    try:
        # First apply tracking to ensure all patching is done
        track_atomic_agents(project_name="tool-span")

        # Verify that BaseTool is patched
        BaseTool = sys.modules["atomic_agents.tools.base_tool"].BaseTool
        print(f"BaseTool patched: {getattr(BaseTool, '__opik_patched__', False)}")

        BaseAgent = sys.modules["atomic_agents.agents.base_agent"].BaseAgent
        agent = BaseAgent()
        agent.run("test")

        opik.flush_tracker()

        assert len(fake_backend.trace_trees) == 1
        trace_tree = fake_backend.trace_trees[0]

        # Debug: print all spans and their types
        print(f"Total spans: {len(trace_tree.spans)}")
        for span in trace_tree.spans:
            print(f"Span: {span.name}, Type: {span.type}")

        # Should have tool spans
        tool_spans = [span for span in trace_tree.spans if span.type == "tool"]
        assert (
            len(tool_spans) > 0
        ), f"No tool spans found. Available spans: {[(s.name, s.type) for s in trace_tree.spans]}"
        assert tool_spans[0].name == "MyTool"
    finally:
        # Restore original method
        base_agent_module.BaseChatAgent._get_and_handle_response = original_method
