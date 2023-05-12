import io
import json

import pytest
from testix import *

from comet_llm import api


@pytest.fixture(autouse=True)
def mock_imports(patch_module):
    patch_module(api, "comet_ml")
    patch_module(api, "converter")
    patch_module(api, "experiment_api")


def test_log_prompt__only_required_data_and_experiment_information():
    pass
    # with Scenario() as s:
    #     s.comet_ml.Experiment(
    #         api_key="an-api-key",
    #         project="a-project",
    #         workspace="a-workspace",
    #         log_code=False,
    #         log_graph=False,
    #         auto_param_logging=False,
    #         auto_metric_logging=False,
    #         log_env_details=False,
    #         log_git_metadata=False,
    #         log_git_patch=False,
    #         log_env_gpu=False,
    #         log_env_host=False,
    #         log_env_cpu=False,
    #         display_summary_level=0,
    #         auto_log_co2=False,
    #     ) >> Fake("experiment")
    #     s.experiment.log_asset(
    #         io.StringIO(json.dumps({"some-node-dictionary"})),
    #         file_name="openai-prompts.json"
    #     )
    #     s.experiment.end()

    #     api.log_prompt(
    #         prompt="a-prompt",
    #         output="an-output",
    #         workspace="a-workspace",
    #         project="a-project",
    #         api_key="an-api-key",
    #     )

    ASSET_DICT_TO_LOG = {
        "_version": 1,
        "chain_nodes": [
            "call-data-dict"
        ],
        "chain_edges": [],
        "chain_context": {},
        "chain_inputs": {
            "final_prompt": "the-prompt",
            "prompt_template": "promt-template",
            "prompt_variables": "prompt"
        },
        "chain_outputs": {
            "output": "the-outputs"
        },
        "metadata": {},
        "start_timestamp": "start-timestamp",
        "end_timestamp": 23.2564,
        "duration": 23.2564
    }

    with Scenario() as s:
        s.ex
        s.converter.call_data_to_dict(
            id=0,
            prompt="the-prompt",
            outputs="the-outputs",
            metadata="the-metadata",
            prompt_template="prompt-template",
            prompt_variables="prompt-variables",
            start_timestamp="start-timestamp",
            end_timestamp="end-timestamp",
            duration="the-duration"
        ) >> "call-data-dict"

        result = api.log_prompt(
            prompt="the-prompt",
            outputs="the-outputs",
            workspace="the-workspace",
            project="the-workspace",
            api_key="the-api-key",
            metadata="the-metadata",
            prompt_template="prompt-template",
            prompt_variables="prompt-variables",
            start_timestamp="start-timestamp",
            end_timestamp="end-timestamp",
            duration="the-duration"
        )