import pytest
from testix import *

from comet_llm.experiment_api import comet_api_client


@pytest.fixture(autouse=True)
def mock_imports(patch_module):
    patch_module(comet_api_client, "comet_ml")
    patch_module(comet_api_client, "config")


@pytest.fixture
def mock_rest_api_class(patch_module):
    patch_module(comet_api_client, "CometAPIClient")


def test_get__happyflow(mock_rest_api_class):
    client_instance = Fake("client")

    with Scenario() as s:
        s.config.comet_url() >> "comet-url"
        s.CometAPIClient("api-key", "comet-url") >> client_instance
        assert comet_api_client.get("api-key") is client_instance
