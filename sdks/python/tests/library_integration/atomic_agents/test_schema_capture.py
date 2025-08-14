"""Verify Pydantic schemas are captured in trace metadata."""

from __future__ import annotations

import sys

from opik.integrations.atomic_agents import track_atomic_agents
from pydantic import BaseModel

import opik


class _Input(BaseModel):
    question: str


class _Output(BaseModel):
    answer: str


def test_schema_serialised_in_metadata(fake_backend):
    track_atomic_agents(project_name="schema-test")

    BaseAgent = sys.modules["atomic_agents.agents.base_agent"].BaseAgent

    class AgentWithSchema(BaseAgent):
        input_schema = _Input
        output_schema = _Output

    agent = AgentWithSchema()
    agent.run({"question": "2+2"})

    opik.flush_tracker()

    assert len(fake_backend.trace_trees) == 1
    trace_tree = fake_backend.trace_trees[0]

    assert "atomic_input_schema" in trace_tree.metadata
    assert trace_tree.metadata["atomic_input_schema"]["title"] == "_Input"
    assert "atomic_output_schema" in trace_tree.metadata
    assert trace_tree.metadata["atomic_output_schema"]["title"] == "_Output"
