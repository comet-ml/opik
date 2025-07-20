"""Verify tracer captures agent input/output Pydantic schemas into metadata."""

from __future__ import annotations

import sys
import types
from typing import List

import pytest
from pydantic import BaseModel, Field  # type: ignore

from opik.api_objects import opik_client
from opik.integrations.atomic_agents import track_atomic_agents


class _Input(BaseModel):
    query: str = Field(...)


class _Output(BaseModel):
    answer: str


@pytest.fixture(autouse=True)
def _stub_atomic_agents_schema(monkeypatch):
    # Create stub module structure
    pkg_root = types.ModuleType("atomic_agents")
    agents_pkg = types.ModuleType("atomic_agents.agents")
    base_pkg = types.ModuleType("atomic_agents.agents.base_agent")

    class BaseAgent:  # pylint: disable=too-few-public-methods
        input_schema = _Input
        output_schema = _Output

        def run(self, payload):  # noqa: D401
            return _Output(answer="42")

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
def _collect_traces(monkeypatch):
    captured: List[dict] = []

    def fake_trace(self, **kwargs):  # noqa: D401
        captured.append(kwargs)
        return types.SimpleNamespace(span=lambda **kw: None, update=lambda **kw: None, end=lambda: None)

    monkeypatch.setattr(opik_client.Opik, "trace", fake_trace, raising=True)
    yield captured


def test_schema_serialised_in_metadata(_stub_atomic_agents_schema, _collect_traces):
    BaseAgent = _stub_atomic_agents_schema  # type: ignore  # noqa: N806
    captured = _collect_traces

    track_atomic_agents(project_name="schema-test")

    BaseAgent().run({"query": "life?"})

    # first message start trace
    meta = captured[0]["metadata"]
    assert "atomic_input_schema" in meta
    assert "atomic_output_schema" in meta
    assert meta["atomic_input_schema"]["title"] == "_Input"
    assert meta["atomic_output_schema"]["title"] == "_Output" 