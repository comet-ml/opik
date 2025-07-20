"""Pytest fixtures for the atomic_agents integration tests."""

import sys
import types

import pytest


def _reset_tracking_state() -> None:
    """Reset global tracking state of the Opik ↔ Atomic Agents integration.
    This helper is required only for the test-suite, so it lives here instead
    of the production codebase.
    """
    try:
        import importlib

        decorators_mod = importlib.import_module(
            "opik.integrations.atomic_agents.decorators"
        )
        if hasattr(decorators_mod, "__IS_TRACKING_ENABLED"):
            decorators_mod.__IS_TRACKING_ENABLED = False  # type: ignore[attr-defined]
    except ModuleNotFoundError:
        pass

    try:
        from atomic_agents.agents.base_agent import BaseAgent

        if hasattr(BaseAgent, "__opik_patched__"):
            delattr(BaseAgent, "__opik_patched__")
    except (ModuleNotFoundError, ImportError, AttributeError):
        pass

    try:
        from atomic_agents.tools.base_tool import BaseTool

        if hasattr(BaseTool, "__opik_patched__"):
            delattr(BaseTool, "__opik_patched__")
    except (ModuleNotFoundError, ImportError, AttributeError):
        pass

    try:
        from atomic_agents.agents.base_agent import BaseChatAgent

        if hasattr(BaseChatAgent, "__opik_patched_llm__"):
            delattr(BaseChatAgent, "__opik_patched_llm__")
    except (ModuleNotFoundError, ImportError, AttributeError):
        pass


@pytest.fixture(autouse=True)
def stub_atomic_agents(monkeypatch):
    """Create a comprehensive stub of the atomic_agents library."""

    pkg_root = types.ModuleType("atomic_agents")
    pkg_root.__path__ = []  # Make it a package

    # agents module
    agents_pkg = types.ModuleType("atomic_agents.agents")
    agents_pkg.__path__ = []
    pkg_root.agents = agents_pkg

    base_agent_pkg = types.ModuleType("atomic_agents.agents.base_agent")

    class MockConfig:
        model = "test_model"

    class BaseChatAgent:
        def __init__(self):
            self.config = MockConfig()
            # Preserve class-level schemas if they exist
            if not hasattr(self, "input_schema"):
                self.input_schema = None
            if not hasattr(self, "output_schema"):
                self.output_schema = None

        def _get_and_handle_response(self, messages):
            return {"content": "world"}

        def get_response(self, messages):
            return {"content": "world"}

        def run(self, payload):
            if payload == "error":
                raise ValueError("boom")
            return self.get_response([])

    base_agent_pkg.BaseChatAgent = BaseChatAgent
    base_agent_pkg.BaseAgent = BaseChatAgent
    agents_pkg.base_agent = base_agent_pkg

    # tools module
    tools_pkg = types.ModuleType("atomic_agents.tools")
    tools_pkg.__path__ = []
    pkg_root.tools = tools_pkg

    base_tool_pkg = types.ModuleType("atomic_agents.tools.base_tool")

    class BaseTool:
        def __init__(self, name: str = "BaseTool"):
            self.name = name

        def run(self, payload):
            return {"ok": True}

    base_tool_pkg.BaseTool = BaseTool
    tools_pkg.base_tool = base_tool_pkg

    sys.modules.update(
        {
            "atomic_agents": pkg_root,
            "atomic_agents.agents": agents_pkg,
            "atomic_agents.agents.base_agent": base_agent_pkg,
            "atomic_agents.tools": tools_pkg,
            "atomic_agents.tools.base_tool": base_tool_pkg,
        }
    )
    yield
    for mod in list(sys.modules.keys()):
        if mod.startswith("atomic_agents"):
            del sys.modules[mod]


@pytest.fixture(autouse=True)
def reset_tracking_state():
    """Reset atomic agents tracking state between tests."""
    _reset_tracking_state()
    yield
    _reset_tracking_state()
