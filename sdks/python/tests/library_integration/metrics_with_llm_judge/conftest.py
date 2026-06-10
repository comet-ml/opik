import pytest

from ...testlib import patch_environ


@pytest.fixture(autouse=True)
def ensure_litellm_monitoring_disabled():
    with patch_environ(add_keys={"OPIK_ENABLE_LITELLM_MODELS_MONITORING": "False"}):
        yield


@pytest.fixture(autouse=True)
def _isolate_from_real_backend(fake_backend):
    """Route every metrics_with_llm_judge test through the in-memory backend
    emulator so tracked-by-default metrics don't spam 401s from the real
    streamer when no OPIK_API_KEY is present.

    Tests that actually inspect traces still declare ``fake_backend`` in their
    signature; pytest resolves the same fixture instance in both places.
    """
    return fake_backend
