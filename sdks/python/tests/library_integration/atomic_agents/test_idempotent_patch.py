"""Calling track_atomic_agents twice should not duplicate patching."""

from __future__ import annotations

import sys

from opik.integrations.atomic_agents import track_atomic_agents

import opik


def test_idempotent(fake_backend):
    # First call patches
    track_atomic_agents(project_name="idempo")

    # Second call should be a no-op
    track_atomic_agents(project_name="idempo")

    BaseAgent = sys.modules["atomic_agents.agents.base_agent"].BaseAgent
    agent = BaseAgent()
    agent.run({})

    opik.flush_tracker()

    # Should only have one trace, not duplicated
    assert len(fake_backend.trace_trees) == 1
