import pytest

from opik.evaluation.resume import checkpoint as resume_checkpoint


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


@pytest.fixture(autouse=True)
def _isolate_resume_checkpoint_writes(monkeypatch):
    """
    Prevent evaluator unit tests from touching ``~/.opik/resume/*.json``.

    Evaluator tests pass mocked experiments whose ``id`` attribute is a
    ``Mock`` — serializing that into a checkpoint JSON file would fail. We
    no-op the writer at the resume.checkpoint level so the integration glue
    can keep its production code path unchanged.

    Tests under ``tests/unit/evaluation/resume/`` override this fixture (see
    ``tests/unit/evaluation/resume/conftest.py``) since they exercise the
    checkpoint module directly.
    """
    monkeypatch.setattr(
        resume_checkpoint, "write_checkpoint", lambda *args, **kwargs: None
    )
