"""Tracer should ignore non-Pydantic schema attributes."""

from __future__ import annotations

import sys

from opik.integrations.atomic_agents import track_atomic_agents

import opik


def test_no_schema_keys(fake_backend):
    track_atomic_agents(project_name="fallback")

    BaseAgent = sys.modules["atomic_agents.agents.base_agent"].BaseAgent

    class AgentWithNoSchema(BaseAgent):
        input_schema = {"type": "object"}
        output_schema = None

    agent = AgentWithNoSchema()
    agent.run("test")

    opik.flush_tracker()

    assert len(fake_backend.trace_trees) == 1
    trace_tree = fake_backend.trace_trees[0]
    assert "atomic_input_schema" not in trace_tree.metadata
    assert "atomic_output_schema" not in trace_tree.metadata
