"""Calling track_atomic_agents twice should not duplicate patching."""

from __future__ import annotations

import sys
import types
from typing import List

import pytest

from opik.api_objects import opik_client
from opik.integrations.atomic_agents import track_atomic_agents


@pytest.fixture(autouse=True)
def _stub(monkeypatch):
    pkg_root = types.ModuleType("atomic_agents")
    agents_pkg = types.ModuleType("atomic_agents.agents")
    base_pkg = types.ModuleType("atomic_agents.agents.base_agent")

    class BaseAgent:  # pylint: disable=too-few-public-methods
        def run(self, payload):  # noqa: D401, ARG002
            return payload

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
def _cap(monkeypatch):
    captured: List[dict] = []

    def fake_trace(self, **kwargs):
        captured.append(kwargs)
        return types.SimpleNamespace(span=lambda **kw: None, update=lambda **kw: None, end=lambda: None)

    monkeypatch.setattr(opik_client.Opik, "trace", fake_trace, raising=True)
    yield captured


def test_idempotent(_stub, _cap):  # noqa: D401
    BaseAgent = _stub  # type: ignore  # noqa: N806
    captured = _cap

    # First call patches
    track_atomic_agents(project_name="idempo")
    # Second call should be noop
    track_atomic_agents(project_name="idempo")

    BaseAgent().run({})

    assert len(captured) == 2  # start & end only 