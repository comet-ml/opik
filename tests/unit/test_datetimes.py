import datetime

import pytest

from comet_llm import datetimes


@pytest.mark.parametrize(
    "timestamp, result",
    [
        (datetime.datetime(2009, 12, 31).timestamp(), False),
        (datetime.datetime(2010, 1, 1).timestamp(), True),
        (datetime.datetime(2030, 1, 2).timestamp(), False),
        (datetime.datetime(2030, 1, 1).timestamp(), True),
        (datetime.datetime(2023, 12, 1).timestamp(), True),
    ]
)
def test_is_valid_timestamp_seconds(timestamp, result):
    assert datetimes.is_valid_timestamp_seconds(timestamp) == result
