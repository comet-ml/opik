import os
import threading

import pytest
import logging

from opik.api_objects import opik_client
from opik.message_processing.replay import replay_manager


@pytest.fixture(autouse=True)
def shutdown_opik_background_threads():
    """Stop Opik background threads after every unit test.

    Constructing a real Opik client starts daemon threads — the streamer's queue
    consumers and batch preprocessor, plus a ReplayManager that periodically probes the
    server through a ConnectionMonitor. Unit tests rarely tear their clients down, so
    across the suite these threads accumulate into hundreds of live threads, the
    ReplayManager probe loops in particular generating ongoing scheduling/IO pressure.
    That contention can push an unrelated test past pytest's per-test --timeout, and with
    --timeout-method=thread the whole run is hard-killed (no report, exit 1).

    Ending the cached global client closes its streamer (idempotent, fire-and-forget — no
    network wait); we then close any ReplayManager left behind by directly-constructed
    clients. close() only signals a stop event, so this never blocks on the network.
    """
    yield

    client = opik_client.get_current_client_raw()
    if client is not None:
        client.end(flush=False)
    opik_client.reset_global_client(end_client=False)

    for thread in threading.enumerate():
        if isinstance(thread, replay_manager.ReplayManager) and thread.is_alive():
            thread.close()
            thread.join(timeout=5)


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

    assert not error_records, (
        f"Errors were logged during test execution: {[r.message for r in error_records]}"
    )


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
