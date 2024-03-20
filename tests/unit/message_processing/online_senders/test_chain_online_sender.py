import json

import box
import pytest
from testix import *

from comet_llm import llm_result
from comet_llm.message_processing import messages
from comet_llm.message_processing.online_senders import chain


@pytest.fixture(autouse=True)
def mock_imports(patch_module):
    patch_module(chain, "io")
    patch_module(chain, "chain")
    patch_module(chain, "state")
    patch_module(chain, "convert")
    #patch_module(chain, "experiment_info")
    patch_module(chain, "experiment_api")
    # patch_module(chain, "app")


def test_send__happyflow():

    CHAIN_DICT = {"some-key": "some-value"}
    message = messages.ChainMessage(
        experiment_information=box.Box(api_key="api-key", workspace="the-workspace", project_name="project-name"),
        tags="the-tags",
        chain_data=CHAIN_DICT,
        duration="chain-duration",
        metadata="the-metadata",
        others={"other-name-1": "other-value-1", "other-name-2": "other-value-2"}
    )


    with Scenario() as s:

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


        assert chain.send(message) == llm_result.LLMResult(id="experiment-id", project_url="project-url")
