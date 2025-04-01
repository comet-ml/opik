from unittest import mock
import logging
from opik import _logging


def test_convert_exception_to_log_message__happy_scenario_with_original_returned_value():
    function = mock.Mock(return_value="return value")
    logger = mock.Mock(spec=["log"])

    convert_exception_to_log_message_decorator = (
        _logging.convert_exception_to_log_message(
            "Error message",
            logger=logger,
            exception_info=True,
            logging_level="some-level",
        )
    )
    decorated_function = convert_exception_to_log_message_decorator(function)

    assert decorated_function() == "return value"

    function.assert_called_once()
    logger.log.assert_not_called()


def test_convert_exception_to_log_message__exception_raised__exception_converted_into_log_message__another_value_returned__log_is_called_with_passed_kwarg():
    function = mock.Mock(side_effect=Exception())
    logger = mock.Mock(spec=["log"])

    convert_exception_to_log_message_decorator = (
        _logging.convert_exception_to_log_message(
            "Error message",
            logger=logger,
            some_log_kwarg="some-log-kwarg",
            return_on_exception="return_value",
            logging_level="some-level",
        )
    )
    decorated_function = convert_exception_to_log_message_decorator(function)

    assert decorated_function() == "return_value"

    function.assert_called_once()
    logger.log.assert_called_once_with(
        "some-level", "Error message", some_log_kwarg="some-log-kwarg"
    )


def test_convert_exception_to_log_message__logging_level_not_set__error_level_used():
    function = mock.Mock(side_effect=Exception())
    logger = mock.Mock(spec=["log"])

    convert_exception_to_log_message_decorator = (
        _logging.convert_exception_to_log_message(
            "Error message",
            logger=logger,
            some_log_kwarg="some-log-kwarg",
            return_on_exception="return_value",
        )
    )
    decorated_function = convert_exception_to_log_message_decorator(function)

    assert decorated_function() == "return_value"

    function.assert_called_once()
    logger.log.assert_called_once_with(
        logging.ERROR,
        "Error message",
        some_log_kwarg="some-log-kwarg",
    )
