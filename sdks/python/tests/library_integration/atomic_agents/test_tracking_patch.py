"""Verify that track_atomic_agents() patches BaseAgent.run and emits traces."""

from __future__ import annotations

import sys
import types
from typing import List

import pytest

from opik.integrations.atomic_agents import track_atomic_agents
from opik.api_objects import opik_client


@pytest.fixture(autouse=True)
def _stub_atomic_agents(monkeypatch):
    """Create a minimal stub of atomic_agents BaseAgent to avoid external dep."""

    # Build dummy package structure
    pkg_root = types.ModuleType("atomic_agents")
    agents_pkg = types.ModuleType("atomic_agents.agents")
    base_pkg = types.ModuleType("atomic_agents.agents.base_agent")

    class BaseAgent:  # pylint: disable=too-few-public-methods
        def run(self, payload):  # noqa: D401
            return {"echo": payload}

    base_pkg.BaseAgent = BaseAgent
    agents_pkg.base_agent = base_pkg  # type: ignore[attr-defined]

    sys.modules.update(
        {
            "atomic_agents": pkg_root,
            "atomic_agents.agents": agents_pkg,
            "atomic_agents.agents.base_agent": base_pkg,
        }
    )

    yield BaseAgent  # type: ignore


@pytest.fixture(autouse=True)
def _capture_traces(monkeypatch):
    captured: List[dict] = []

    def fake_trace(self, **kwargs):  # noqa: D401
        captured.append(kwargs)
        return types.SimpleNamespace(span=lambda **kw: None, update=lambda **kw: None, end=lambda: None)

    monkeypatch.setattr(opik_client.Opik, "trace", fake_trace, raising=True)
    yield captured


def test_patch_emits_root_trace(_stub_atomic_agents, _capture_traces):  # noqa: D401
    BaseAgent = _stub_atomic_agents  # type: ignore  # noqa: N806
    captured = _capture_traces

    track_atomic_agents(project_name="lib-test")

    agent = BaseAgent()
    result = agent.run({"foo": "bar"})

    assert result == {"echo": {"foo": "bar"}}

    assert len(captured) == 2  # start & end
    start, end = captured
    assert start["project_name"] == "lib-test"
    assert start["name"] == "BaseAgent"
    assert end["project_name"] == "lib-test" 