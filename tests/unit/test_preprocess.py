import pytest
from testix import *

from comet_llm import exceptions, preprocess


@pytest.fixture(autouse=True)
def mock_imports(patch_module):
    patch_module(preprocess, "datetimes")


def test_timestamps__start_timestamp_not_None_and_has_invalid_format__exception_raised():
    with Scenario() as s:
        s.datetimes.is_valid_timestamp_seconds("invalid-start-timestamp") >> False

        with pytest.raises(exceptions.CometLLMException):
            preprocess.timestamps("invalid-start-timestamp", None)


def test_timestamps__end_timestamp_not_None_and_has_invalid_format__exception_raised():
    with Scenario() as s:
        s.datetimes.is_valid_timestamp_seconds("invalid-end-timestamp") >> False

        with pytest.raises(exceptions.CometLLMException):
            preprocess.timestamps(None, "invalid-end-timestamp")


def test_timestamps__both_timestamps_are_not_None__start_is_greater_than_end__exception_raised():
    START_TIMESTAMP = 10
    END_TIMESTAMP = 5

    with Scenario() as s:
        s.datetimes.is_valid_timestamp_seconds(START_TIMESTAMP) >> True
        s.datetimes.is_valid_timestamp_seconds(END_TIMESTAMP) >> True

        with pytest.raises(exceptions.CometLLMException):
            preprocess.timestamps(START_TIMESTAMP, END_TIMESTAMP)


def test_timestamps__timestamps_are_correct__timestamps_converted_to_milliseconds():
    START_TIMESTAMP = 5
    END_TIMESTAMP = 10

    with Scenario() as s:
        s.datetimes.is_valid_timestamp_seconds(START_TIMESTAMP) >> True
        s.datetimes.is_valid_timestamp_seconds(END_TIMESTAMP) >> True


        assert preprocess.timestamps(START_TIMESTAMP, END_TIMESTAMP) == (
            START_TIMESTAMP*1000,
            END_TIMESTAMP*1000
        )


def test_timestamps__both_None__Nones_returned():
    assert preprocess.timestamps(None, None) == (
        None,
        None
    )

def test_timestamps__only_start_is_None__none_and_end_converted_to_milliseconds_returned():
    END_TIMESTAMP = 10

    with Scenario() as s:
        s.datetimes.is_valid_timestamp_seconds(END_TIMESTAMP) >> True

        assert preprocess.timestamps(None, END_TIMESTAMP) == (
            None,
            END_TIMESTAMP*1000
        )