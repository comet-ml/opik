import pytest
from opik import context_storage
from opik.api_objects import opik_client
from .testlib import backend_emulator_message_processor
from opik.message_processing import streamer_constructors


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
def fake_streamer():
    try:
        fake_message_processor_ = (
            backend_emulator_message_processor.BackendEmulatorMessageProcessor()
        )
        streamer = streamer_constructors.construct_streamer(
            message_processor=fake_message_processor_,
            n_consumers=1,
            use_batching=True,
        )

        yield streamer, fake_message_processor_
    finally:
        streamer.close(timeout=5)
