import pytest
import requests

from comet_llm import exceptions
from comet_llm.experiment_api import request_exception_wrapper


def test_reraiser_no_exceptions():
    @request_exception_wrapper.wrap
    def f():
        return "return-value"

    assert f() == "return-value"


def test_reraiser__request_exception_caught__comet_exception_raised():
    @request_exception_wrapper.wrap
    def f():
        raise requests.RequestException

    with pytest.raises(exceptions.CometLLMException):
        f()


def test_reraiser__generic_exception_not_caught():
    @request_exception_wrapper.wrap
    def f():
        raise Exception

    with pytest.raises(Exception):
        f()