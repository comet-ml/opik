import box
import pytest
from testix import *

from comet_llm import llm_result
from comet_llm.chains import version
from comet_llm.prompts import api


@pytest.fixture(autouse=True)
def mock_imports(patch_module):
    patch_module(api, "convert")
    patch_module(api, "experiment_info")
    patch_module(api, "preprocess")
    patch_module(api, "app")
    patch_module(api, "messages")
    patch_module(api, "message_processors")
    patch_module(api, "config")


def test_log_prompt__happyflow():
    EXPECTED_ASSET_DICT_TO_LOG = {
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
        "metadata": "the-metadata",
        "start_timestamp": "preprocessed-timestamp",
        "end_timestamp": "preprocessed-timestamp",
        "chain_duration": "the-duration"
    }
    MESSAGE = """
    CometLLM requires an API key. Please provide it as the
    api_key argument to log_prompt or as an environment
    variable named COMET_API_KEY
    """

    EXPECTED_LLM_RESULT = llm_result.LLMResult(
        project_url="project-url",
        id="the-id"
    )

    experiment_info = box.Box(
        api_key="api-key", workspace="the-workspace", project_name="project-name",
    )

    with Scenario() as s:
        s.preprocess.timestamp("the-timestamp") >> "preprocessed-timestamp"
        s.config.offline_enabled() >> False
        s.experiment_info.get(
            "passed-api-key",
            "passed-workspace",
            "passed-project-name",
            api_key_not_found_message=MESSAGE,
        ) >> experiment_info

        s.convert.call_data_to_dict(
            prompt="the-prompt",
            outputs="the-outputs",
            metadata="the-metadata",
            prompt_template="prompt-template",
            prompt_template_variables="prompt-template-variables",
            start_timestamp="preprocessed-timestamp",
            end_timestamp="preprocessed-timestamp",
            duration="the-duration"
        ) >> "CALL-DATA-DICT"

        s.messages.generate_id() >> "message-id"
        s.messages.PromptMessage(
            id="message-id",
            experiment_info_=experiment_info,
            prompt_asset_data=EXPECTED_ASSET_DICT_TO_LOG,
            duration="the-duration",
            metadata="the-metadata",
            tags="the-tags"
        ) >> "prompt-message"
        s.message_processors.MESSAGE_PROCESSOR.process("prompt-message") >> EXPECTED_LLM_RESULT
        s.app.SUMMARY.add_log("project-url", "prompt")

        result = api.log_prompt(
            prompt="the-prompt",
            output="the-outputs",
            workspace="passed-workspace",
            project="passed-project-name",
            tags="the-tags",
            api_key="passed-api-key",
            metadata="the-metadata",
            prompt_template="prompt-template",
            prompt_template_variables="prompt-template-variables",
            timestamp="the-timestamp",
            duration="the-duration"
        )

        assert result is EXPECTED_LLM_RESULT
