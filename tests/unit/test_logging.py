import pytest
from testix import *

import comet_llm.logging


@pytest.fixture
def mock_log_once_at_level(patch_module):
    patch_module(comet_llm.logging, "log_once_at_level")


def test_convert_exception_to_log_message__happy_scenario_with_returned_value():
    NOT_USED = None

    @comet_llm.logging.log_message_on_error(
        NOT_USED,
        NOT_USED,
        NOT_USED
    )
    def func():
        return "return-value"

    assert func() == "return-value"


def test_log_message_on_error__exception_raised__message_logged():
    @comet_llm.logging.log_message_on_error(
        Fake("logger"),
        logging_level="logging-level",
        message="the-message",
        some_kwarg_key="some-kwarg-value"
    )
    def func():
        raise Exception()

    with Scenario() as s:
        s.logger.log("logging-level", "the-message", some_kwarg_key="some-kwarg-value")
        with pytest.raises(Exception):
            func()


def test_log_message_on_error__exception_raised__log_once_is_True__log_once_at_level_called(mock_log_once_at_level):
    @comet_llm.logging.log_message_on_error(
        Fake("logger"),
        logging_level="logging-level",
        message="the-message",
        log_once=True,
        some_kwarg_key="some-kwarg-value"
    )
    def func():
        raise Exception()

    with Scenario() as s:
        s.log_once_at_level(Fake("logger"), "logging-level", "the-message", some_kwarg_key="some-kwarg-value")

        with pytest.raises(Exception):
            func()