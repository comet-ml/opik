import os

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


@pytest.fixture
def files_to_remove():
    """Helper to clean up temporary files that was created during tests."""
    created_files = []

    yield created_files

    # cleanup phase
    for path in created_files:
        if path and os.path.exists(path):
            os.unlink(path)
