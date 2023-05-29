import box
import pytest
from testix import *

from comet_llm.chains import api


@pytest.fixture(autouse=True)
def mock_imports(patch_module):
    patch_module(api, "chain")


def test_start_chain__happyflow():
    assert 0
    with Scenario() as s:

        api.start_chain(
            inputs="the-inputs",
            api_key="api-key",
            workspace="the-workspace",
            project_name="project-name",
            metadata="the-metadata",
        )


def test_end_chain__happyflow():
    assert 0
    with Scenario() as s:

        api.end_chain(
            outputs="the-outputs",
            metadata="the-metadata",
        )