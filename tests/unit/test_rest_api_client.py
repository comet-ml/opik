import pytest
from testix import *

from comet_llm import rest_api_client


@pytest.fixture(autouse=True)
def mock_imports(patch_module):
    patch_module(rest_api_client, "comet_ml")
    patch_module(rest_api_client, "config")
    patch_module(rest_api_client, "RestApiClient")


def test_get__api_key_is_None__api_key_taken_from_config():
    config_instance = Fake("config_instance")
    client_instance = Fake("client")

    with Scenario() as s:
        s.comet_ml.get_config() >> Fake("config_instance")
        s.comet_ml.get_api_key(None, config_instance) >> "api-key"
        s.RestApiClient("api-key") >> client_instance

        assert rest_api_client.get() is client_instance


def test_get__api_key_passed__use_it_for_instantiating_client():
    client_instance = Fake("client")

    with Scenario() as s:
        s.RestApiClient("api-key") >> client_instance

        assert rest_api_client.get("api-key") is client_instance