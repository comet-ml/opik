import json
import pytest
import box
from testix import *

from comet_llm import experiment_api


@pytest.fixture(autouse=True)
def mock_imports(patch_module):
    patch_module(experiment_api, "rest_api_client")


def _construct(experiment_key):
    with Scenario() as s:
        s.rest_api_client.get("api-key") >> Fake("client_instance")
        s.client_instance.create_experiment(
            "the-workspace",
            "project-name"
        ) >> box.Box(text=json.dumps({"experimentKey": experiment_key }))

        tested = experiment_api.ExperimentAPI(
            api_key="api-key",
            workspace="the-workspace",
            project_name="project-name",
        )
        
    return tested


def test_log_asset():
    tested = _construct("experiment-key")

    with Scenario() as s:
        s.client_instance.log_experiment_asset(
            "experiment-key",
            file_name="file-name",
            file_data="file-data",
        )
        tested.log_asset(
            file_name="file-name",
            file_data="file-data"
        )


def test_stop():
    tested = _construct("experiment-key")

    with Scenario() as s:
        s.client_instance.stop_experiment("experiment-key")

        tested.stop()