"""Library integration tests for Atomic Agents tracer."""

from __future__ import annotations

import types
from typing import List

import pytest

from opik.integrations.atomic_agents import OpikAtomicAgentsTracer
from opik.api_objects import opik_client


@pytest.fixture(autouse=True)
def _capture_opik_traces(monkeypatch):
    """Collect trace payloads emitted by Opik client without hitting network."""

    captured: List[dict] = []

    def fake_trace(self, **kwargs):  # noqa: D401
        captured.append(kwargs)
        # minimal dummy object satisfying .span/.update/.end that Opik tests expect
        return types.SimpleNamespace(span=lambda **kw: None, update=lambda **kw: None, end=lambda: None)

    monkeypatch.setattr(opik_client.Opik, "trace", fake_trace, raising=True)

    yield captured


def test_basic_trace_emission(_capture_opik_traces):  # noqa: D401
    captured = _capture_opik_traces

    tracer = OpikAtomicAgentsTracer(project_name="demo-project")
    with tracer:
        pass

    # Expect 2 messages: start + end
    assert len(captured) == 2
    start_msg, end_msg = captured

    assert start_msg["name"] == "atomic_agents_run"
    assert start_msg["project_name"] == "demo-project"
    assert end_msg["project_name"] == "demo-project" 