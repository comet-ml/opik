import importlib
from typing import Any

from pydantic_core.core_schema import none_schema
import pytest

# Import public API
import opik

# Also keep reference to internal module for explicit reloads
runtime_config = importlib.import_module("opik.runtime_config")


@pytest.mark.parametrize("first,second", [(False, True), (True, False)])
def test_set_and_get(first: bool, second: bool) -> none_schema:
    """Runtime tracing flag should persist until changed."""
    opik.set_tracing_active(first)
    assert opik.is_tracing_active() is first

    opik.set_tracing_active(second)
    assert opik.is_tracing_active() is second


def test_reset_to_config_default(monkeypatch: Any) -> None:
    """Reset should fall back to OpikConfig.track_disable value."""
    # Stub OpikConfig to force tracing disabled via static config
    from opik import config as _config_module

    class DummyConfig(_config_module.OpikConfig):
        track_disable: bool = True

    monkeypatch.setattr(_config_module, "OpikConfig", DummyConfig, raising=False)

    # Reload module to clear cached value and pick up new config class
    importlib.reload(runtime_config)

    assert opik.is_tracing_active() is False

    opik.set_tracing_active(True)
    assert opik.is_tracing_active() is True

    opik.reset_tracing_to_config_default()
    assert opik.is_tracing_active() is False
