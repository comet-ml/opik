from opik.error_tracking.error_filtering import filter_by_count

import pytest


@pytest.mark.parametrize("max_count", [0, 1, 10])
def test_filter_by_count__errors(
    max_count, mocked_warning_event, mocked_error_event, basic_hint
):
    tested = filter_by_count.FilterByCount(max_count=max_count, level="error")

    for _ in range(max_count):
        assert tested.process_event(mocked_error_event, basic_hint) is True

    assert tested.process_event(mocked_error_event, basic_hint) is False
    # should be neutral about warnings
    assert tested.process_event(mocked_warning_event, basic_hint) is True

    assert mocked_warning_event.get() == "warning"
    assert mocked_error_event.get() == "error"


@pytest.mark.parametrize("max_count", [0, 1, 10])
def test_filter_by_count__warning(
    max_count, mocked_warning_event, mocked_error_event, basic_hint
):
    tested = filter_by_count.FilterByCount(max_count=max_count, level="warning")

    for _ in range(max_count):
        assert tested.process_event(mocked_warning_event, basic_hint) is True

    assert tested.process_event(mocked_warning_event, basic_hint) is False
    # should be neutral about errors
    assert tested.process_event(mocked_error_event, basic_hint) is True

    assert mocked_warning_event.get() == "warning"
    assert mocked_error_event.get() == "error"
