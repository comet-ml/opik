import io
import json

import box
import pytest
from testix import *

from comet_llm.chains import api


@pytest.fixture(autouse=True)
def mock_imports(patch_module):
    patch_module(api, "io")
    patch_module(api, "chain")
    patch_module(api, "state")
    patch_module(api, "experiment_info")
    patch_module(api, "experiment_api")


def test_start_chain__happyflow():
    MESSAGE = """
    CometLLM requires an API key. Please provide it as the
    api_key argument to comet_llm.start_chain or as an environment
    variable named COMET_API_KEY
    """
    with Scenario() as s:
        s.experiment_info.get(
            "api-key",
            "the-workspace",
            "project-name",
            api_key_not_found_message=MESSAGE,
        )>> "experiment-info"
        s.chain.Chain(inputs="the-inputs", metadata="the-metadata", experiment_info="experiment-info") >> "the-chain"
        s.state.set_global_chain("the-chain")

        api.start_chain(
            inputs="the-inputs",
            api_key="api-key",
            workspace="the-workspace",
            project_name="project-name",
            metadata="the-metadata",
        )


def test_end_chain__happyflow():
    experiment_info = box.Box(api_key="api-key", workspace="the-workspace", project_name="project-name")
    
    with Scenario() as s:
        s.state.get_global_chain() >> Fake("global_chain", experiment_info=experiment_info)
        s.global_chain.set_outputs(outputs="the-outputs", metadata="the-metadata")
        s.global_chain.as_dict() >> "chain-dict"

        s.experiment_api.ExperimentAPI(
            api_key="api-key",
            workspace="the-workspace",
            project_name="project-name"
        ) >> Fake("experiment_api_instance")

        s.io.StringIO(json.dumps("chain-dict")) >> "asset-data"
        s.experiment_api_instance.log_asset_with_io(
            name="comet_llm_data.json",
            file="asset-data"
        )

        api.end_chain(
            outputs="the-outputs",
            metadata="the-metadata",
        )