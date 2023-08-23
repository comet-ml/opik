import pytest
from testix import *

from comet_llm import exceptions
from comet_llm.prompts import preprocess


@pytest.fixture(autouse=True)
def mock_imports(patch_module):
    patch_module(preprocess, "datetimes")


def test_timestamp__timestamp_is_not_None_and_has_invalid_format__exception_raised():
    with Scenario() as s:
        s.datetimes.is_valid_timestamp_seconds("invalid-timestamp") >> False

        with pytest.raises(exceptions.CometLLMException):
            preprocess.timestamp("invalid-timestamp")


def test_timestamp__timestamp_is_None__local_timestamp_returned():
    with Scenario() as s:
        s.datetimes.local_timestamp() >> "local-timestamp"

        preprocess.timestamp(None) == "local-timestamp"


def test_timestamp__timestamp_is_correct__returned_converted_to_milliseconds():
    TIMESTAMP = 10
    with Scenario() as s:
        s.datetimes.is_valid_timestamp_seconds(TIMESTAMP) >> True

        preprocess.timestamp(TIMESTAMP) == TIMESTAMP * 1000