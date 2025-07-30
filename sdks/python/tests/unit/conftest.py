import pytest
import logging


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
