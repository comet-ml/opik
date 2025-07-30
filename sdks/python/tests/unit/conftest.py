import pytest
import logging
import opik

@pytest.fixture
def capture_log(caplog):
    logger = logging.getLogger("opik")
    logger.setLevel("INFO")
    logger.propagate = True  # Propagate so pytest logging capture works

    yield caplog

    logger.propagate = False


@pytest.fixture
def capture_log_debug(caplog):
    logger = logging.getLogger("opik")
    logger.setLevel("DEBUG")
    logger.propagate = True  # Propagate so pytest logging capture works

    yield caplog

    logger.propagate = False

@pytest.fixture(autouse=True)
def reset_tracing_to_config_default():
    opik.reset_tracing_to_config_default()
    yield
    opik.reset_tracing_to_config_default()

