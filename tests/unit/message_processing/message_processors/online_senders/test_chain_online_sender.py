import json

import box
import pytest
from testix import *

from comet_llm import llm_result
from comet_llm.message_processing import messages
from comet_llm.message_processing.message_processors.online_senders import chain, constants

NOT_USED = None


@pytest.fixture(autouse=True)
def mock_imports(patch_module):
    patch_module(chain, "io")
    patch_module(chain, "chain")
    patch_module(chain, "state")
    patch_module(chain, "convert")
    patch_module(chain, "experiment_api")
    patch_module(chain, "comet_api_client")
    patch_module(chain, "url_helpers")


@pytest.fixture
def mock_v2_backend_version(patch_module):
    patch_module(constants, "V2_BACKEND_VERSION", 10)
    return 10


def test_send__v1_backend__happyflow(mock_v2_backend_version):

    CHAIN_DICT = {"some-key": "some-value"}
    message = messages.ChainMessage(
        id=NOT_USED,
        experiment_info_=box.Box(api_key="api-key", workspace="the-workspace", project_name="project-name"),
        tags="the-tags",
        chain_data=CHAIN_DICT,
        duration="chain-duration",
        metadata="the-metadata",
        others={"other-name-1": "other-value-1", "other-name-2": "other-value-2"}
    )

    V1_BACKEND_VERSION = mock_v2_backend_version - 1
    with Scenario() as s:
        s.comet_api_client.get("api-key") >> box.Box(backend_version=V1_BACKEND_VERSION)

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


def test_send__v2_backend__happyflow(mock_v2_backend_version):
    CHAIN_DICT = {"some-key": "some-value"}
    message = messages.ChainMessage(
        id="experiment-id",
        experiment_info_=box.Box(api_key="api-key", workspace="the-workspace", project_name="project-name"),
        tags="the-tags",
        chain_data=CHAIN_DICT,
        duration="chain-duration",
        metadata="the-metadata",
        others={"other-name-1": "other-value-1", "other-name-2": "other-value-2"}
    )

    V2_BACKEND_VERSION = mock_v2_backend_version
    with Scenario() as s:
        s.comet_api_client.get("api-key") >> Fake("client", backend_version=V2_BACKEND_VERSION)
        s.convert.chain_metadata_to_flat_parameters(
            "the-metadata",
        ) >> {"parameter-key-1": "value-1", "parameter-key-2": "value-2"}
        s.client.log_chain(
            experiment_key="experiment-id",
            chain_asset=CHAIN_DICT,
            workspace="the-workspace",
            project="project-name",
            tags="the-tags",
            metrics={"chain_duration": "chain-duration"},
            parameters={"parameter-key-1": "value-1", "parameter-key-2": "value-2"},
            others={"other-name-1": "other-value-1", "other-name-2": "other-value-2"}
        ) >> box.Box(link="experiment-url")
        s.url_helpers.experiment_to_project_url("experiment-url") >> "project-url"

        assert chain.send(message) == llm_result.LLMResult(id="experiment-id", project_url="project-url")
