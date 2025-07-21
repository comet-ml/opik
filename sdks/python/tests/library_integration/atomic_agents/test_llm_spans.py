"""Ensure Atomic Agents LLM calls create child llm spans."""

from __future__ import annotations

import sys

from opik.integrations.atomic_agents import track_atomic_agents

import opik


def test_llm_span_creation(fake_backend) -> None:
    """Verify `_get_and_handle_response` patch creates an `llm` span."""

    track_atomic_agents(project_name="test-project")

    BaseChatAgent = sys.modules["atomic_agents.agents.base_agent"].BaseChatAgent
    agent = BaseChatAgent()
    agent.run("test")

    opik.flush_tracker()

    assert len(fake_backend.trace_trees) == 1
    trace_tree = fake_backend.trace_trees[0]

    llm_spans = [span for span in trace_tree.spans if span.type == "llm"]
    assert len(llm_spans) > 0, "No LLM span was created"

    llm_span = llm_spans[0]
    assert llm_span.name == "test_model"
    assert llm_span.output["content"] == "world"
