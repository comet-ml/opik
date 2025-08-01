"""Verify that track_atomic_agents() patches BaseAgent.run and emits traces."""

from __future__ import annotations

import sys

from opik.integrations.atomic_agents import track_atomic_agents

import opik


def test_patch_emits_root_trace(fake_backend):
    track_atomic_agents(project_name="lib-test")

    BaseAgent = sys.modules["atomic_agents.agents.base_agent"].BaseAgent
    agent = BaseAgent()
    agent.run({"input": "test"})

    opik.flush_tracker()

    assert len(fake_backend.trace_trees) == 1
    trace_tree = fake_backend.trace_trees[0]
    assert trace_tree.project_name == "lib-test"
    assert trace_tree.name == "BaseAgent"
