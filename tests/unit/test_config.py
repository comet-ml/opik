import pytest
from testix import *

from comet_llm import config

@pytest.fixture(autouse=True)
def mock_imports(patch_module):
    patch_module(config, "comet_ml")


def test_init__happyflow():
    with Scenario() as s:
        s.comet_ml.init(
            api_key="api-key",
            workspace="the-workspace",
            project="the-project"
        )
        config.init(
            api_key="api-key",
            workspace="the-workspace",
            project="the-project"
        )

def test_init__not_set_arguments_not_passed_to_comet_ml_init():
    with Scenario() as s:
        s.comet_ml.init(api_key="api-key")
        config.init(api_key="api-key")