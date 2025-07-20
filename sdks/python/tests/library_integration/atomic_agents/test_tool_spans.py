"""Ensure Atomic Agents tool executions create child tool spans."""

from __future__ import annotations

import sys
import types
from typing import List

import pytest

from opik.api_objects import opik_client
from opik.integrations.atomic_agents import track_atomic_agents


@pytest.fixture(autouse=True)
def _stub(monkeypatch):
    # Stub agent & tool classes
    pkg_root = types.ModuleType("atomic_agents")

    # --- tools
    tools_pkg = types.ModuleType("atomic_agents.tools")
    base_tool_pkg = types.ModuleType("atomic_agents.tools.base_tool")

    class BaseTool:  # pylint: disable=too-few-public-methods
        def __init__(self, name: str = "BaseTool"):
            self.name = name

        def run(self, payload):  # noqa: D401, ARG002
            return {"ok": True}

    base_tool_pkg.BaseTool = BaseTool
    tools_pkg.base_tool = base_tool_pkg  # type: ignore[attr-defined]

    # --- agents
    agents_pkg = types.ModuleType("atomic_agents.agents")
    base_agent_pkg = types.ModuleType("atomic_agents.agents.base_agent")

    class DummyAgent:  # pylint: disable=too-few-public-methods
        def __init__(self):
            self.tool = BaseTool("EchoTool")

        def run(self, payload):  # noqa: D401, ARG002
            return self.tool.run(payload)

    base_agent_pkg.BaseAgent = DummyAgent
    agents_pkg.base_agent = base_agent_pkg  # type: ignore[attr-defined]

    # register in sys.modules
    sys.modules.update(
        {
            "atomic_agents": pkg_root,
            "atomic_agents.tools": tools_pkg,
            "atomic_agents.tools.base_tool": base_tool_pkg,
            "atomic_agents.agents": agents_pkg,
            "atomic_agents.agents.base_agent": base_agent_pkg,
        }
    )

    yield DummyAgent  # type: ignore


@pytest.fixture(autouse=True)
def _capture(monkeypatch):
    captured: List[dict] = []

    def fake_trace(self, **kwargs):
        captured.append(kwargs)
        return types.SimpleNamespace(span=lambda **kw: None, update=lambda **kw: None, end=lambda: None)

    monkeypatch.setattr(opik_client.Opik, "trace", fake_trace, raising=True)
    monkeypatch.setattr(opik_client.Opik, "span", fake_trace, raising=True)
    yield captured


def test_tool_span_created(_stub, _capture):
    Agent = _stub  # type: ignore  # noqa: N806
    captured = _capture

    track_atomic_agents(project_name="tool-span")

    Agent().run({"input": "hi"})

    # Order: trace(start), span(start), span(end), trace(end)
    assert len(captured) == 4
    types_seq = [msg.get("type", "trace") for msg in captured]
    assert types_seq == ["trace", "tool", "tool", "trace"] 