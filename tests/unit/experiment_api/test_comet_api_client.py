import box
import pytest
from testix import *

from comet_llm.experiment_api import comet_api_client


@pytest.fixture(autouse=True)
def mock_imports(patch_module):
    patch_module(comet_api_client, "comet_ml")
    patch_module(comet_api_client, "config")
    patch_module(comet_api_client, "requests")


@pytest.fixture
def mock_rest_api_class(patch_module):
    patch_module(comet_api_client, "CometAPIClient")


def test_get__happyflow(mock_rest_api_class):
    client_instance = Fake("client")
    tested_function_undecorated = comet_api_client.get.__wrapped__

    with Scenario() as s:
        s.config.comet_url() >> "comet-url"
        s.requests.Session() >> "the-session"
        s.config.tls_verification_enabled() >> True
        s.CometAPIClient("api-key", "comet-url", "the-session") >> client_instance

        assert tested_function_undecorated("api-key") is client_instance


def test_get__tls_verification_disabled__set_session_property_verify_to_False(mock_rest_api_class):
    client_instance = Fake("client")
    session = box.Box()

    tested_function_undecorated = comet_api_client.get.__wrapped__

    with Scenario() as s:
        s.config.comet_url() >> "comet-url"
        s.requests.Session() >> session
        s.config.tls_verification_enabled() >> False
        s.CometAPIClient("api-key", "comet-url", session) >> client_instance

        assert tested_function_undecorated("api-key") is client_instance
        assert session.verify is False
