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
def capture_log_check_errors(caplog):
    """Fixture to capture logs and check for errors during test execution."""
    # Set level on root opik logger - this affects all child loggers
    logger = logging.getLogger("opik")
    logger.setLevel(logging.INFO)
    logger.propagate = True  # Propagate so pytest logging capture works

    # Configure caplog to capture all logs at INFO level and above
    # This captures logs from all loggers, not just "opik"
    # caplog.set_level(logging.INFO)

    yield caplog

    logger.propagate = False

    # Get records from the "call" phase (the actual test execution)
    # caplog.records gets cleared, but get_records() retrieves phase-specific records
    call_records = caplog.get_records("call")

    # Check for error records after test execution
    # Filter for records from opik namespace and its children
    error_records = [
        record
        for record in call_records
        if record.levelno >= logging.ERROR and record.name.startswith("opik")
    ]

    assert (
        not error_records
    ), f"Errors were logged during test execution: {[r.message for r in error_records]}"


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
