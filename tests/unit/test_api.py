import json

import box
import pytest
from testix import *

from comet_llm import api
from comet_llm.chains import version


@pytest.fixture(autouse=True)
def mock_imports(patch_module):
    patch_module(api, "comet_ml")
    patch_module(api, "convert")
    patch_module(api, "experiment_api")
    patch_module(api, "experiment_info")
    patch_module(api, "flatten_dict")
    patch_module(api, "io")


def test_log_prompt__happyflow():
    ASSET_DICT_TO_LOG = {
        "version": version.ASSET_FORMAT_VERSION,
        "chain_nodes": [
            "CALL-DATA-DICT"
        ],
        "chain_inputs": {
            "final_prompt": "the-prompt",
            "prompt_template": "prompt-template",
            "prompt_template_variables": "prompt-template-variables"
        },
        "chain_outputs": {
            "output": "the-outputs"
        },
        "category": "single_prompt",
        "metadata": {},
        "start_timestamp": "start-timestamp",
        "end_timestamp": "end-timestamp",
        "chain_duration": "the-duration"
    }
    MESSAGE = """
    CometLLM requires an API key. Please provide it as the
    api_key argument to log_prompt or as an environment
    variable named COMET_API_KEY
    """

    with Scenario() as s:
        s.experiment_info.get(
            "passed-api-key",
            "passed-workspace",
            "passed-project-name",
            api_key_not_found_message=MESSAGE,
        )>> box.Box(
            api_key="api-key", workspace="the-workspace", project_name="project-name",
        )
        s.experiment_api.ExperimentAPI(
            api_key="api-key",
            workspace="the-workspace",
            project_name="project-name"
        ) >> Fake("experiment_api_instance")

        s.convert.call_data_to_dict(
            prompt="the-prompt",
            outputs="the-outputs",
            metadata="the-metadata",
            prompt_template="prompt-template",
            prompt_template_variables="prompt-template-variables",
            start_timestamp="start-timestamp",
            end_timestamp="end-timestamp",
            duration="the-duration"
        ) >> "CALL-DATA-DICT"

        s.io.StringIO(json.dumps(ASSET_DICT_TO_LOG)) >> "asset-data"
        s.experiment_api_instance.log_asset_with_io(
            name="comet_llm_data.json",
            file="asset-data"
        )

        s.convert.chain_metadata_to_flat_dict(
            "the-metadata",
            "start-timestamp",
            "end-timestamp",
            "the-duration"
        ) >> {"parameter-key-1": "value-1", "parameter-key-2": "value-2"}

        s.experiment_api_instance.log_parameter("parameter-key-1", "value-1")
        s.experiment_api_instance.log_parameter("parameter-key-2", "value-2")

        api.log_prompt(
            prompt="the-prompt",
            output="the-outputs",
            workspace="passed-workspace",
            project="passed-project-name",
            api_key="passed-api-key",
            metadata="the-metadata",
            prompt_template="prompt-template",
            prompt_template_variables="prompt-template-variables",
            start_timestamp="start-timestamp",
            end_timestamp="end-timestamp",
            duration="the-duration"
        )