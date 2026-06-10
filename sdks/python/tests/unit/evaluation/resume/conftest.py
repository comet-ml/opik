import pytest


@pytest.fixture(autouse=True)
def _isolate_resume_checkpoint_writes():
    """
    Override the parent conftest's checkpoint-write isolation.

    Resume tests exercise the checkpoint module directly (via the
    ``isolated_checkpoint_dir`` fixture in ``test_checkpoint.py``) or pass
    explicit writers through dependency injection. They need the real
    ``resume.checkpoint.write_checkpoint`` symbol left intact.
    """
    yield
