import pytest
import requests
from testix import *

from comet_llm import exceptions
from comet_llm.experiment_api import request_exception_wrapper


@pytest.fixture(autouse=True)
def mock_imports(patch_module):
    patch_module(request_exception_wrapper, "config")


def test_reraiser_no_exceptions():
    @request_exception_wrapper.wrap()
    def f():
        return "return-value"

    assert f() == "return-value"


def test_reraiser__request_exception_caught__comet_exception_raised():
    @request_exception_wrapper.wrap()
    def f():
        raise requests.RequestException

    with pytest.raises(exceptions.CometLLMException):
        f()

def test_reraiser__request_exception_caught__comet_exception_raised():
    @request_exception_wrapper.wrap()
    def f():
        raise requests.RequestException

    with pytest.raises(exceptions.CometLLMException):
        f()


def test_reraiser__on_prem_check_enabled__request_exception_caught__on_prem_detected__comet_exception_raised_with_additional_message():
    @request_exception_wrapper.wrap(check_on_prem=True)
    def f():
        raise requests.RequestException

    with Scenario() as s:
        s.config.comet_url() >> "not-comet-cloud-url"
        with pytest.raises(exceptions.CometLLMException):
            f()


def test_reraiser__on_prem_check_enabled__request_exception_caught__on_prem_not_detected__comet_exception_raised_without_additional_message():
    @request_exception_wrapper.wrap(check_on_prem=True)
    def f():
        raise requests.RequestException

    COMET_CLOUD_URL = "https://www.comet.com/clientlib/"

    with Scenario() as s:
        s.config.comet_url() >> COMET_CLOUD_URL
        with pytest.raises(exceptions.CometLLMException):
            f()