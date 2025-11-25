import importlib
import pytest

import opik
from opik import opik_context

runtime_config = importlib.import_module("opik.tracing_runtime_config")


@pytest.fixture(autouse=True)
def reset_tracing_state():
    runtime_config.reset_tracing_to_config_default()
    yield
    runtime_config.reset_tracing_to_config_default()


def test_update_current_span_noop_when_tracing_disabled():
    opik.set_tracing_active(False)
    opik_context.update_current_span(name="test-span")


def test_update_current_span_error_when_tracing_enabled():
    opik.set_tracing_active(True)
    with pytest.raises(opik.exceptions.OpikException):
        opik_context.update_current_span(name="test-span")


def test_update_current_trace_noop_when_tracing_disabled():
    opik.set_tracing_active(False)
    opik_context.update_current_trace(name="test-trace")


def test_update_current_trace_error_when_tracing_enabled():
    opik.set_tracing_active(True)
    with pytest.raises(opik.exceptions.OpikException):
        opik_context.update_current_trace(name="test-trace")
