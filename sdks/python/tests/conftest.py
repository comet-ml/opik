import random
import string
import tempfile
from typing import cast

import numpy as np

from unittest import mock
import pytest

from opik import context_storage
from opik.api_objects import opik_client
from opik.message_processing import streamer_constructors
from . import testlib
from .testlib import backend_emulator_message_processor, noop_file_upload_manager


@pytest.fixture(autouse=True)
def clear_context_storage():
    yield
    context_storage.clear_all()


@pytest.fixture(autouse=True)
def shutdown_cached_client_after_test():
    yield
    if opik_client.get_client_cached.cache_info().currsize > 0:
        opik_client.get_client_cached().end()
        opik_client.get_client_cached.cache_clear()


@pytest.fixture
def patch_streamer():
    streamer = None
    try:
        fake_message_processor_ = (
            backend_emulator_message_processor.BackendEmulatorMessageProcessor()
        )
        fake_upload_manager = noop_file_upload_manager.NoopFileUploadManager()
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
def patch_streamer_without_batching():
    streamer = None
    try:
        fake_message_processor_ = (
            backend_emulator_message_processor.BackendEmulatorMessageProcessor()
        )
        fake_upload_manager = noop_file_upload_manager.NoopFileUploadManager()
        streamer = streamer_constructors.construct_streamer(
            message_processor=fake_message_processor_,
            n_consumers=1,
            use_batching=False,
            file_upload_manager=fake_upload_manager,
            max_queue_size=None,
        )

        yield streamer, fake_message_processor_
    finally:
        if streamer is not None:
            streamer.close(timeout=5)


@pytest.fixture
def fake_backend(patch_streamer):
    """
    Patches the function that creates an instance of Streamer under the hood of Opik.
    As a result, instead of sending data to the backend, it's being passed to
    the backend emulator, which uses this data to build span and trace trees.

    The resulting trees can be accessed via `fake_backend.trace_trees` or
    `fake_backend.span_trees` and then used for comparing with expected span/trace trees.

    The trees are built via special classes stored in testlib.models.
    """
    streamer, fake_message_processor_ = patch_streamer

    fake_message_processor_ = cast(
        backend_emulator_message_processor.BackendEmulatorMessageProcessor,
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


@pytest.fixture
def fake_backend_without_batching(patch_streamer_without_batching):
    """
    Same as fake_backend but must be used when batching is not supported
    (e.g., when there are Span/Trace update requests)
    """
    streamer, fake_message_processor_ = patch_streamer_without_batching

    fake_message_processor_ = cast(
        backend_emulator_message_processor.BackendEmulatorMessageProcessor,
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


def random_chars(n: int = 6) -> str:
    return "".join(random.choice(string.ascii_letters) for _ in range(n))


@pytest.fixture()
def configure_opik_local_env_vars():
    with testlib.patch_environ(
        {
            "OPIK_URL_OVERRIDE": "http://localhost:5173/api",
        }
    ):
        yield


@pytest.fixture()
def configure_opik_not_configured():
    with testlib.patch_environ(
        add_keys={},
        remove_keys=[
            "OPIK_URL_OVERRIDE",
            "OPIK_API_KEY",
            "OPIK_WORKSPACE",
        ],
    ):
        yield


@pytest.fixture()
def temp_file_15mb():
    file_size = 15 * 1024 * 1024
    with tempfile.NamedTemporaryFile(delete=True) as f:
        f.write(np.random.bytes(file_size))
        f.flush()

        yield f
