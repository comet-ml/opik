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
        ) >> {"experimentKey": experiment_key, "link": "project-url/experiment-key-part"}

        tested = experiment_api.ExperimentAPI(
            api_key="api-key",
            workspace="the-workspace",
            project_name="project-name",
        )
        assert tested.link == "project-url/experiment-key-part"
        assert tested.project_url == "project-url"

    return tested


def test_log_asset():
    tested = _construct("experiment-key")

    with Scenario() as s:
        s.client_instance.log_experiment_asset_with_io(
            "experiment-key",
            name="the-name",
            file="the-io",
            asset_type="asset-type",
        )
        tested.log_asset_with_io(
            name="the-name",
            file="the-io",
            asset_type="asset-type",
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


def test_log_metric():
    tested = _construct("experiment-key")

    with Scenario() as s:
        s.client_instance.log_experiment_metric(
            "experiment-key",
            name="metric-name",
            value="metric-value",
        )
        tested.log_metric(
            name="metric-name",
            value="metric-value",
        )


def test_log_tags():
    tested = _construct("experiment-key")

    with Scenario() as s:
        s.client_instance.log_experiment_tags(
            "experiment-key",
            tags="the-tags",
        )
        tested.log_tags(
            "the-tags",
        )
