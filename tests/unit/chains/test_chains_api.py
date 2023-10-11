import io
import json

import box
import pytest
from testix import *

from comet_llm import llm_result
from comet_llm.chains import api


@pytest.fixture(autouse=True)
def mock_imports(patch_module):
    patch_module(api, "io")
    patch_module(api, "chain")
    patch_module(api, "state")
    patch_module(api, "convert")
    patch_module(api, "experiment_info")
    patch_module(api, "experiment_api")
    patch_module(api, "app")


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
        s.chain.Chain(
            inputs="the-inputs",
            metadata="the-metadata",
            experiment_info="experiment-info",
            tags="the-tags",
        ) >> "the-chain"
        s.state.set_global_chain("the-chain")

        api.start_chain(
            inputs="the-inputs",
            api_key="api-key",
            workspace="the-workspace",
            project="project-name",
            metadata="the-metadata",
            tags="the-tags",
        )


def test_end_chain__happyflow():
    experiment_info = box.Box(api_key="api-key", workspace="the-workspace", project_name="project-name")

    CHAIN_DICT = {
        "other-keys": "other-values",
        "metadata": "the-metadata",
        "start_timestamp": "start-timestamp",
        "end_timestamp": "end-timestamp",
        "chain_duration": "chain-duration"
    }
    with Scenario() as s:
        s.state.get_global_chain() >> Fake(
            "global_chain",
            experiment_info=experiment_info,
            tags="the-tags",
            others={"other-name-1": "other-value-1", "other-name-2": "other-value-2"}
        )
        s.global_chain.set_outputs(outputs="the-outputs", metadata="the-metadata")
        s.global_chain.as_dict() >> CHAIN_DICT

        s.experiment_api.ExperimentAPI.create_new(
            api_key="api-key",
            workspace="the-workspace",
            project_name="project-name"
        ) >> Fake("experiment_api_instance", project_url="project-url", id="experiment-id")

        s.experiment_api_instance.log_tags("the-tags")

        s.io.StringIO(json.dumps(CHAIN_DICT)) >> "asset-data"
        s.experiment_api_instance.log_asset_with_io(
            name="comet_llm_data.json",
            file="asset-data",
            asset_type="llm_data",
        )
        s.experiment_api_instance.log_metric(name="chain_duration", value="chain-duration")
        s.convert.chain_metadata_to_flat_parameters(
            "the-metadata",
        ) >> {"parameter-key-1": "value-1", "parameter-key-2": "value-2"}

        s.experiment_api_instance.log_parameter("parameter-key-1", "value-1")
        s.experiment_api_instance.log_parameter("parameter-key-2", "value-2")
        s.experiment_api_instance.log_other("other-name-1", "other-value-1")
        s.experiment_api_instance.log_other("other-name-2", "other-value-2")

        s.app.SUMMARY.add_log("project-url", "chain")

        result = api.end_chain(
            outputs="the-outputs",
            metadata="the-metadata",
        )

        assert result == llm_result.LLMResult(id="experiment-id", project_url="project-url")
