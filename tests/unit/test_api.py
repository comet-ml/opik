import pytest
import json
import io

from testix import *
from comet_llm import api


@pytest.fixture(autouse=True)
def mock_imports(patch_module):
    patch_module(api, "comet_ml")


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