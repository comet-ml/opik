import pytest

from ...testlib import patch_environ


@pytest.fixture(autouse=True)
def ensure_litellm_monitoring_disabled():
    with patch_environ(add_keys={"OPIK_ENABLE_LITELLM_MODELS_MONITORING": "False"}):
        yield
