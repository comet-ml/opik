"""Ensure tracer captures error_info when agent.run raises."""

from __future__ import annotations

import sys

import pytest
from opik.integrations.atomic_agents import track_atomic_agents

import opik


def test_error_info_present(fake_backend):
    track_atomic_agents(project_name="err-test")

    BaseAgent = sys.modules["atomic_agents.agents.base_agent"].BaseAgent
    agent = BaseAgent()

    with pytest.raises(ValueError, match="boom"):
        agent.run("error")

    opik.flush_tracker()

    assert len(fake_backend.trace_trees) == 1
    trace_tree = fake_backend.trace_trees[0]

    assert trace_tree.error_info is not None
    assert trace_tree.error_info["exception_type"] == "ValueError"
    assert "boom" in trace_tree.error_info["message"]
