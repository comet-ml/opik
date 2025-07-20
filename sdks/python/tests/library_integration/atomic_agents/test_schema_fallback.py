"""Tracer should ignore non-Pydantic schema attributes."""

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
        input_schema = {"type": "object"}  # not a Pydantic model
        output_schema = None

        def run(self, payload):  # noqa: D401, ARG002
            return {"ok": True}

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


def test_no_schema_keys(_stub, _cap):  # noqa: D401
    BaseAgent = _stub  # type: ignore  # noqa: N806
    captured = _cap

    track_atomic_agents(project_name="fallback")
    BaseAgent().run({})

    meta = captured[0]["metadata"]
    assert "atomic_input_schema" not in meta
    assert "atomic_output_schema" not in meta 