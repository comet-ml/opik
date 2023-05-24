import pytest
from testix import *

from comet_llm.experiment_api import experiment_api


@pytest.fixture(autouse=True)
def mock_imports(patch_module):
    patch_module(experiment_api, "comet_api_client")


def _construct(experiment_key):
    with Scenario() as s:
        s.comet_api_client.get("api-key") >> Fake("client_instance")
        s.client_instance.create_experiment(
            "LLM",
            "the-workspace",
            "project-name",
        ) >> {"experimentKey": experiment_key }

        tested = experiment_api.ExperimentAPI(
            api_key="api-key",
            workspace="the-workspace",
            project_name="project-name",
        )

    return tested


def test_log_asset():
    tested = _construct("experiment-key")

    with Scenario() as s:
        s.client_instance.log_experiment_asset_with_io(
            "experiment-key",
            name="the-name",
            file="the-io",
        )
        tested.log_asset_with_io(
            name="the-name",
            file="the-io"
        )


def test_log_parameter():
    tested = _construct("experiment-key")

    with Scenario() as s:
        s.client_instance.log_experiment_parameter(
            "experiment-key",
            name="parameter-name",
            value="parameter-value",
        )
        tested.log_parameter(
            name="parameter-name",
            value="parameter-value",
        )
