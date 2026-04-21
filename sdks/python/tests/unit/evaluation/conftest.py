import pytest


@pytest.fixture(autouse=True)
def _isolate_from_real_backend(fake_backend):
    """Route every evaluation unit test through the in-memory backend emulator.

    Many evaluation paths instantiate tracked metrics (``track=True`` by
    default), which install an ``opik.track`` decorator that produces traces
    via the global streamer. Without this fixture, tests that never opt into
    ``fake_backend`` would build a real HTTP streamer and spam the test output
    with 401s when the pipeline tries to push to a non-existent backend.

    Tests that inspect traces still declare ``fake_backend`` in their signature;
    pytest resolves the same fixture instance in both places.
    """
    return fake_backend
