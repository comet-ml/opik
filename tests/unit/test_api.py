import json

import box
import pytest
from testix import *

from comet_llm import api, messages


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
        "_version": api.ASSET_FORMAT_VERSION,
        "chain_nodes": [
            "CALL-DATA-DICT"
        ],
        "chain_edges": [],
        "chain_context": {},
        "chain_inputs": {
            "final_prompt": "the-prompt",
            "prompt_template": "prompt-template",
            "prompt_template_variables": "prompt-template-variables"
        },
        "chain_outputs": {
            "output": "the-outputs"
        },
        "metadata": {},
        "start_timestamp": "start-timestamp",
        "end_timestamp": "end-timestamp",
        "duration": "the-duration"
    }

    with Scenario() as s:
        s.experiment_info.get(
            "passed-api-key",
            "passed-workspace",
            "passed-project-name",
            api_key_not_found_message=messages.LOG_PROMPT_API_KEY_NOT_FOUND,
        )>> box.Box(
            api_key="api-key", workspace="the-workspace", project_name="project-name",
        )
        s.experiment_api.ExperimentAPI(
            api_key="api-key",
            workspace="the-workspace",
            project_name="project-name"
        ) >> Fake("experiment_api_instance")

        s.convert.call_data_to_dict(
            id=0,
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

        s.flatten_dict.flatten("the-metadata", reducer="dot") >> {
            "flattened_key1": "value1",
            "flattened_key2": "value2",
            "flattened_key3-WILL-NOT-BE-LOGGED-BECAUSE-NONE": None
        }

        s.experiment_api_instance.log_parameter("start_timestamp", "start-timestamp")
        s.experiment_api_instance.log_parameter("end_timestamp", "end-timestamp")
        s.experiment_api_instance.log_parameter("duration", "the-duration")
        s.experiment_api_instance.log_parameter("flattened_key1", "value1")
        s.experiment_api_instance.log_parameter("flattened_key2", "value2")

        api.log_prompt(
            prompt="the-prompt",
            outputs="the-outputs",
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