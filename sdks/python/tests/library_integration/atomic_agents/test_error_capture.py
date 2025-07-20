"""Ensure tracer captures error_info when agent.run raises."""

from __future__ import annotations

import sys
import types
from typing import List

import pytest

from opik.api_objects import opik_client
from opik.integrations.atomic_agents import track_atomic_agents


@pytest.fixture(autouse=True)
def _stub_agent(monkeypatch):
    pkg_root = types.ModuleType("atomic_agents")
    agents_pkg = types.ModuleType("atomic_agents.agents")
    base_pkg = types.ModuleType("atomic_agents.agents.base_agent")

    class BaseAgent:  # pylint: disable=too-few-public-methods
        def run(self, payload):  # noqa: D401, ARG002
            raise ValueError("boom")

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
def _capture(monkeypatch):
    captured: List[dict] = []

    def fake_trace(self, **kwargs):
        captured.append(kwargs)
        return types.SimpleNamespace(span=lambda **kw: None, update=lambda **kw: None, end=lambda: None)

    monkeypatch.setattr(opik_client.Opik, "trace", fake_trace, raising=True)
    yield captured


def test_error_info_present(_stub_agent, _capture):  # noqa: D401
    BaseAgent = _stub_agent  # type: ignore  # noqa: N806
    captured = _capture

    track_atomic_agents(project_name="err-test")

    with pytest.raises(ValueError):
        BaseAgent().run({})

    assert len(captured) == 2
    end_meta = captured[1]
    assert end_meta["metadata"]["created_from"] == "atomic_agents"
    assert end_meta["error_info"]["exception_type"] == "ValueError" 