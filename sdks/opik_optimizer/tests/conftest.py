"""Test configuration and fixtures for opik_optimizer tests.

This file recreates the necessary fixtures from the Python SDK tests.
"""

import logging
import sys
from pathlib import Path
from typing import cast
from unittest import mock

import pytest

# Add Python SDK and Python SDK tests to path
python_sdk_path = Path(__file__).parent.parent.parent / "python" / "src"
python_tests_path = Path(__file__).parent.parent.parent / "python" / "tests"

sys.path.insert(0, str(python_tests_path))

# Import required modules from Python SDK
from opik import context_storage  # noqa: E402
from opik.api_objects import opik_client  # noqa: E402
from opik.message_processing import streamer_constructors  # noqa: E402

# Import test utilities from Python SDK
from testlib.backend_emulator_message_processor import BackendEmulatorMessageProcessor  # noqa: E402
from testlib.noop_file_upload_manager import NoopFileUploadManager  # noqa: E402


@pytest.fixture(autouse=True)
def clear_context_storage():  # type: ignore[no-untyped-def]
    """Clear context storage after each test."""
    yield
    context_storage.clear_all()


@pytest.fixture(autouse=True)
def shutdown_cached_client_after_test():  # type: ignore[no-untyped-def]
    """Shutdown cached client after each test."""
    yield
    if opik_client.get_client_cached.cache_info().currsize > 0:
        opik_client.get_client_cached().end()
        opik_client.get_client_cached.cache_clear()


@pytest.fixture
def capture_log(caplog: pytest.LogCaptureFixture):  # type: ignore[no-untyped-def]
    """Capture logs from opik_optimizer logger."""
    logger = logging.getLogger("opik_optimizer")
    logger.setLevel("INFO")
    logger.propagate = True  # Propagate so pytest logging capture works

    yield caplog

    logger.propagate = False


@pytest.fixture
def patch_streamer():  # type: ignore[no-untyped-def]
    """Create a streamer with fake backend for testing."""
    streamer = None
    try:
        fake_message_processor_ = BackendEmulatorMessageProcessor()
        fake_upload_manager = NoopFileUploadManager()
        streamer = streamer_constructors.construct_streamer(
            message_processor=fake_message_processor_,
            n_consumers=1,
            use_batching=True,
            file_upload_manager=fake_upload_manager,
            max_queue_size=None,
        )
        yield streamer, fake_message_processor_
    finally:
        if streamer is not None:
            streamer.close(timeout=5)


@pytest.fixture
def fake_backend(patch_streamer):  # type: ignore[no-untyped-def]
    """
    Fake backend emulator that captures traces without network calls.

    Access via: fake_backend.trace_trees, fake_backend.span_trees
    """
    streamer, fake_message_processor_ = patch_streamer

    fake_message_processor_ = cast(
        BackendEmulatorMessageProcessor,
        fake_message_processor_,
    )

    mock_construct_online_streamer = mock.Mock()
    mock_construct_online_streamer.return_value = streamer

    with mock.patch.object(
        streamer_constructors,
        "construct_online_streamer",
        mock_construct_online_streamer,
    ):
        yield fake_message_processor_
