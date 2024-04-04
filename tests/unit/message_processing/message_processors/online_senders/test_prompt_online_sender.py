import json

import box
import pytest
from testix import *

from comet_llm import llm_result
from comet_llm.chains import version
from comet_llm.message_processing import messages
from comet_llm.message_processing.message_processors.online_senders import prompt


@pytest.fixture(autouse=True)
def mock_imports(patch_module):
    patch_module(prompt, "comet_ml")
    patch_module(prompt, "convert")
    patch_module(prompt, "experiment_api")
    patch_module(prompt, "experiment_info")
    patch_module(prompt, "flatten_dict")
    patch_module(prompt, "datetimes")
    patch_module(prompt, "io")
    patch_module(prompt, "preprocess")
    patch_module(prompt, "app")
    patch_module(prompt, "messages")
    patch_module(prompt, "message_processing_api")



def test_send__happyflow():
    message = messages.PromptMessage(
        experiment_info_=box.Box(api_key="api-key", workspace="the-workspace", project_name="project-name"),
        prompt_asset_data={"asset-dict-key": "asset-dict-value"},
        duration="the-duration",
        metadata="the-metadata",
        tags="the-tags"
    )

    with Scenario() as s:
        s.experiment_api.ExperimentAPI.create_new(
            api_key="api-key",
            workspace="the-workspace",
            project_name="project-name"
        ) >> Fake("experiment_api_instance", project_url="project-url", id="experiment-id")

        s.io.StringIO(json.dumps({"asset-dict-key": "asset-dict-value"})) >> "asset-data"
        s.experiment_api_instance.log_asset_with_io(
            name="comet_llm_data.json",
            file="asset-data",
            asset_type="llm_data",
        )
        s.experiment_api_instance.log_tags("the-tags")
        s.experiment_api_instance.log_metric("chain_duration", "the-duration")
        s.convert.chain_metadata_to_flat_parameters("the-metadata") >> {
            "parameter-key-1": "value-1",
            "parameter-key-2": "value-2"
        }

        s.experiment_api_instance.log_parameter("parameter-key-1", "value-1")
        s.experiment_api_instance.log_parameter("parameter-key-2", "value-2")

        prompt.send(message)