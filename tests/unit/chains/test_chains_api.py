import io
import json

import box
import pytest
from testix import *

from comet_llm import llm_result
from comet_llm.chains import api
from comet_llm.message_processing import messages


@pytest.fixture(autouse=True)
def mock_imports(patch_module):
    patch_module(api, "chain")
    patch_module(api, "state")
    patch_module(api, "experiment_info")
    patch_module(api, "app")
    patch_module(api, "messages")
    patch_module(api, "message_processing_api")


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
        "some-keys": "some-values",
        "metadata": "the-metadata",
        "start_timestamp": "start-timestamp",
        "end_timestamp": "end-timestamp",
        "chain_duration": "chain-duration"
    }

    EXPECTED_LLM_RESULT = llm_result.LLMResult(
        project_url="project-url",
        id="the-id"
    )
    with Scenario() as s:
        s.state.get_global_chain() >> Fake(
            "global_chain",
            experiment_info=experiment_info,
            tags="the-tags",
            others={"other-name-1": "other-value-1", "other-name-2": "other-value-2"}
        )
        s.global_chain.set_outputs(outputs="the-outputs", metadata="the-metadata")
        s.global_chain.as_dict() >> CHAIN_DICT

        s.messages.ChainMessage(
            experiment_information=experiment_info,
            tags="the-tags",
            chain_data=CHAIN_DICT,
            duration="chain-duration",
            metadata="the-metadata",
            others={"other-name-1": "other-value-1", "other-name-2": "other-value-2"},
        ) >> "chain-message"

        s.message_processing_api.MESSAGE_PROCESSOR.process("chain-message") >> EXPECTED_LLM_RESULT
        s.app.SUMMARY.add_log("project-url", "chain")

        assert api.end_chain(outputs="the-outputs", metadata="the-metadata") is EXPECTED_LLM_RESULT
