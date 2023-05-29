
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

    timer = datetimes.Timer()

    with Scenario() as s:
        s.local_timestamp() >> START_TIMESTAMP
        s.local_timestamp() >> END_TIMESTAMP

        timer.start()
        timer.stop()

        assert timer.start_timestamp == START_TIMESTAMP
        assert timer.end_timestamp == END_TIMESTAMP
        assert timer.duration == DURATION


def test_timer__start_and_stop_not_called__all_fields_are_None():
    timer = datetimes.Timer()

    assert timer.duration is None
    assert timer.end_timestamp is None
    assert timer.start_timestamp is None


def test_timer__stop_was_not_called__only_start_timestamp_is_not_None():
    timer = datetimes.Timer()

    with Scenario() as s:
        s.local_timestamp() >> "start-timestamp"

        timer.start()

        assert timer.duration is None
        assert timer.end_timestamp is None
        assert timer.start_timestamp == "start-timestamp"


def test_timer__start_resets_other_fields():
    timer = datetimes.Timer()

    START_TIMESTAMP = 10
    END_TIMESTAMP = 25
    DURATION = 15

    timer = datetimes.Timer()

    with Scenario() as s:
        s.local_timestamp() >> START_TIMESTAMP
        s.local_timestamp() >> END_TIMESTAMP
        s.local_timestamp() >> "new-start-timestamp"

        timer.start()
        timer.stop()

        assert timer.start_timestamp == START_TIMESTAMP
        assert timer.end_timestamp == END_TIMESTAMP
        assert timer.duration == DURATION

        timer.start()

        assert timer.duration is None
        assert timer.end_timestamp is None
        assert timer.start_timestamp == "new-start-timestamp"
