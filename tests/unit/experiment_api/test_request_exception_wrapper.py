import json

import box
import pytest
import requests
from testix import *

from comet_llm.exceptions import exceptions
from comet_llm.experiment_api import request_exception_wrapper


@pytest.fixture(autouse=True)
def mock_imports(patch_module):
    patch_module(request_exception_wrapper, "config")
    patch_module(request_exception_wrapper, "failed_response_handler")


def test_wrap_no_exceptions():
    @request_exception_wrapper.wrap()
    def f():
        return "return-value"

    assert f() == "return-value"


def test_wrap__request_exception_caught__comet_exception_raised():
    @request_exception_wrapper.wrap()
    def f():
        raise requests.RequestException

    with pytest.raises(exceptions.CometLLMException):
        f()


def test_wrap__on_prem_check_enabled__request_exception_caught__on_prem_detected__comet_exception_raised_with_additional_message():
    @request_exception_wrapper.wrap(check_on_prem=True)
    def f():
        raise requests.RequestException

    with Scenario() as s:
        s.config.comet_url() >> "https://not.comet.cloud/ddf/"
        with pytest.raises(exceptions.CometLLMException):
            f()


def test_wrap__on_prem_check_enabled__request_exception_caught__on_prem_not_detected__comet_exception_raised_without_additional_message():
    @request_exception_wrapper.wrap(check_on_prem=True)
    def f():
        raise requests.RequestException

    COMET_CLOUD_URL = "https://www.comet.com/clientlib/"

    with Scenario() as s:
        s.config.comet_url() >> COMET_CLOUD_URL
        with pytest.raises(exceptions.CometLLMException):
            f()


def test_wrap__request_exception_non_llm_project_sdk_code__log_specifc_message_in_exception():
    response = box.Box(text=json.dumps({"sdk_error_code": 34323}))

    @request_exception_wrapper.wrap()
    def f():
        exception = requests.RequestException()
        exception.response = response
        raise exception

    expected_log_message = "Failed to send prompt to the specified project as it is not an LLM project, please specify a different project name."

    with Scenario() as s:
        s.failed_response_handler.handle(response) >> expected_log_message
        with pytest.raises(exceptions.CometLLMException) as excinfo:
            f()

    assert excinfo.value.args == (expected_log_message, )