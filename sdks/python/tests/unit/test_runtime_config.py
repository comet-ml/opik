import importlib
from typing import Generator, Any

import pytest

import opik

runtime_config = importlib.import_module("opik.runtime_config")


@pytest.fixture(autouse=True)
def reset_tracing_state() -> Generator[None, None, None]:
    runtime_config.reset_tracing_to_config_default()
    yield
    runtime_config.reset_tracing_to_config_default()


@pytest.mark.parametrize("first,second", [(False, True), (True, False)])
def test_set_and_get(first: bool, second: bool) -> None:
    opik.set_tracing_active(first)
    assert opik.is_tracing_active() is first

    opik.set_tracing_active(second)
    assert opik.is_tracing_active() is second


def test_reset_to_config_default(monkeypatch: Any) -> None:
    from opik import config as _config_module

    class DummyConfig(_config_module.OpikConfig):
        track_disable: bool = True

    monkeypatch.setattr(_config_module, "OpikConfig", DummyConfig, raising=False)
    importlib.reload(runtime_config)

    assert opik.is_tracing_active() is False

    opik.set_tracing_active(True)
    assert opik.is_tracing_active() is True

    opik.reset_tracing_to_config_default()
    assert opik.is_tracing_active() is False
