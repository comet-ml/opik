"""Minimal tests for OpikAtomicAgentsTracer."""

from __future__ import annotations

from opik.integrations.atomic_agents import OpikAtomicAgentsTracer

import opik


def test_basic_trace_emission(fake_backend):
    tracer = OpikAtomicAgentsTracer(project_name="demo-project")
    tracer.start(name="test-trace")
    tracer.end()

    opik.flush_tracker()

    assert len(fake_backend.trace_trees) == 1
    trace_tree = fake_backend.trace_trees[0]
    assert trace_tree.project_name == "demo-project"
    assert trace_tree.name == "test-trace"
