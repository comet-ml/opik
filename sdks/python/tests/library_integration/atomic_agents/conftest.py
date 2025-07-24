"""Pytest fixtures for the atomic_agents integration tests."""

import sys
import types

import pytest


def _reset_tracking_state() -> None:
    """Reset global tracking state of the Opik ↔ Atomic Agents integration.
    This helper is required only for the test-suite, so it lives here instead
    of the production codebase.
    """
    import importlib

    decorators_mod = importlib.import_module(
        "opik.integrations.atomic_agents.decorators"
    )
    if hasattr(decorators_mod, "__IS_TRACKING_ENABLED"):
        decorators_mod.__IS_TRACKING_ENABLED = False  # type: ignore[attr-defined]

    try:
        from atomic_agents.agents.base_agent import BaseAgent

        if hasattr(BaseAgent, "__opik_patched__"):
            delattr(BaseAgent, "__opik_patched__")

        # LLM patch flag is stored on the same class
        if hasattr(BaseAgent, "__opik_patched_llm__"):
            delattr(BaseAgent, "__opik_patched_llm__")
    except (ModuleNotFoundError, ImportError, AttributeError):
        BaseAgent = None  # noqa: F841

    try:
        from atomic_agents.lib.base.base_tool import BaseTool  # type: ignore

        if hasattr(BaseTool, "__opik_patched__"):
            delattr(BaseTool, "__opik_patched__")
    except (ModuleNotFoundError, ImportError, AttributeError):
        pass


@pytest.fixture(autouse=True)
def stub_atomic_agents(monkeypatch):
    """Create a comprehensive stub of the atomic_agents library."""

    pkg_root = types.ModuleType("atomic_agents")
    pkg_root.__path__ = []

    agents_pkg = types.ModuleType("atomic_agents.agents")
    agents_pkg.__path__ = []
    pkg_root.agents = agents_pkg

    base_agent_pkg = types.ModuleType("atomic_agents.agents.base_agent")

    class MockConfig:
        model = "test_model"

    class BaseAgent:
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

    base_agent_pkg.BaseAgent = BaseAgent
    agents_pkg.base_agent = base_agent_pkg

    # lib.base.base_tool path (new)
    from types import ModuleType

    class BaseTool:
        def __init__(self, name: str = "BaseTool"):
            self.name = name

        def run(self, payload):
            return {"ok": True}

    lib_pkg: ModuleType = types.ModuleType("atomic_agents.lib")
    lib_pkg.__path__ = []
    pkg_root.lib = lib_pkg

    base_lib_pkg: ModuleType = types.ModuleType("atomic_agents.lib.base")
    base_lib_pkg.__path__ = []
    lib_pkg.base = base_lib_pkg

    base_tool_lib_pkg: ModuleType = types.ModuleType("atomic_agents.lib.base.base_tool")
    base_tool_lib_pkg.BaseTool = BaseTool
    base_lib_pkg.base_tool = base_tool_lib_pkg

    sys.modules.update(
        {
            "atomic_agents": pkg_root,
            "atomic_agents.agents": agents_pkg,
            "atomic_agents.agents.base_agent": base_agent_pkg,
            "atomic_agents.lib": lib_pkg,
            "atomic_agents.lib.base": base_lib_pkg,
            "atomic_agents.lib.base.base_tool": base_tool_lib_pkg,
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
