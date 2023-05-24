
import pytest
from testix import *

from comet_llm import datetimes


@pytest.fixture(autouse=True)
def mock_local_timestamp(patch_module):
    patch_module(datetimes, "local_timestamp")


def test_timer__happyflow():
    START_TIMESTAMP = 10
    END_TIMESTAMP = 25
    DURATION = 15

    with Scenario() as s:
        s.local_timestamp() >> START_TIMESTAMP
        s.local_timestamp() >> END_TIMESTAMP

        timer = datetimes.Timer()
        timer.stop()

        assert timer.start_timestamp == START_TIMESTAMP
        assert timer.end_timestamp == END_TIMESTAMP
        assert timer.duration == DURATION


def test_timer__stop_was_not_called__only_start_timestamp_is_not_None():
    with Scenario() as s:
        s.local_timestamp() >> "start-timestamp"
        timer = datetimes.Timer()

        assert timer.duration is None
        assert timer.end_timestamp is None
        assert timer.start_timestamp == "start-timestamp"
