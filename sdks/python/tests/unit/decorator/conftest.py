import opik
import pytest


@pytest.fixture(autouse=True)
def reset_tracing_to_config_default():
    opik.reset_tracing_to_config_default()
    yield
    opik.reset_tracing_to_config_default()
