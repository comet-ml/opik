import json

import box
import pytest
from testix import *

from comet_llm import llm_result
from comet_llm.chains import version
from comet_llm.message_processing import messages
from comet_llm.message_processing.message_processors.online_senders import prompt, constants


@pytest.fixture(autouse=True)
def mock_imports(patch_module):
    patch_module(prompt, "convert")
    patch_module(prompt, "experiment_api")
    patch_module(prompt, "io")
    patch_module(prompt, "preprocess")
    patch_module(prompt, "comet_api_client")
    patch_module(prompt, "url_helpers")

@pytest.fixture
def mock_v2_backend_version(patch_module):
    patch_module(constants, "V2_BACKEND_VERSION", 10)
    return 10


def test_send__v1_backend__happyflow(mock_v2_backend_version):
    message = messages.PromptMessage(
        id="id-which-wont-be-used",
        experiment_info_=box.Box(api_key="api-key", workspace="the-workspace", project_name="project-name"),
        prompt_asset_data={"asset-dict-key": "asset-dict-value"},
        duration="the-duration",
        metadata="the-metadata",
        tags="the-tags"
    )

    V1_BACKEND_VERSION = mock_v2_backend_version - 1

    with Scenario() as s:
        s.comet_api_client.get("api-key") >> box.Box(backend_version=V1_BACKEND_VERSION)

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

        assert prompt.send(message)


def test_send__v2_backend__happyflow(mock_v2_backend_version):
    message = messages.PromptMessage(
        id="experiment-id",
        experiment_info_=box.Box(api_key="api-key", workspace="the-workspace", project_name="project-name"),
        prompt_asset_data={"asset-dict-key": "asset-dict-value"},
        duration="the-duration",
        metadata="the-metadata",
        tags="the-tags"
    )

    V2_BACKEND_VERSION = mock_v2_backend_version
    with Scenario() as s:
        s.comet_api_client.get("api-key") >> Fake("client", backend_version=V2_BACKEND_VERSION)
        s.convert.chain_metadata_to_flat_parameters(
            "the-metadata",
        ) >> {"parameter-key-1": "value-1", "parameter-key-2": "value-2"}
        s.client.log_chain(
            experiment_key="experiment-id",
            chain_asset={"asset-dict-key": "asset-dict-value"},
            workspace="the-workspace",
            project="project-name",
            tags="the-tags",
            metrics={"chain_duration": "the-duration"},
            parameters={"parameter-key-1": "value-1", "parameter-key-2": "value-2"},
        ) >> box.Box(link="experiment-url")
        s.url_helpers.experiment_to_project_url("experiment-url") >> "project-url"

        assert prompt.send(message) == llm_result.LLMResult(id="experiment-id", project_url="project-url")
