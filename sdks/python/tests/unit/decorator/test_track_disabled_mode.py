from opik import opik_context
from opik.decorator import tracker
import opik

from ...testlib import patch_environ
import pytest


def test_track_disabled_mode__nothing_logged__happyflow(fake_backend):
    tracker_instance = tracker.OpikTrackDecorator()

    with patch_environ({"OPIK_TRACK_DISABLE": "true"}):

        @tracker_instance.track
        def f_inner():
            return 42

        @tracker_instance.track
        def f_outer():
            f_inner()

        f_outer()
        tracker.flush_tracker()

    assert len(fake_backend.trace_trees) == 0
    assert len(fake_backend.span_trees) == 0


def test_track_disabled_mode__get_current_span_and_trace_called__spans_and_trace_are_none__nothing_logged(
    fake_backend,
):
    """
    When tracing is disabled, ``get_current_span_data()`` and ``get_current_trace_data()``
    must return ``None``. This safeguards existing scripts from unexpected failures
    when tracing is turned off.
    """
    tracker_instance = tracker.OpikTrackDecorator()

    with patch_environ({"OPIK_TRACK_DISABLE": "true"}):

        @tracker_instance.track
        def f():
            assert opik_context.get_current_span_data() is None
            assert opik_context.get_current_trace_data() is None

        f()
        tracker.flush_tracker()

    assert len(fake_backend.trace_trees) == 0
    assert len(fake_backend.span_trees) == 0


def test_track_disabled_mode__update_current_span_and_trace_called__no_errors_raised__nothing_logged(
    fake_backend,
):
    tracker_instance = tracker.OpikTrackDecorator()

    with patch_environ({"OPIK_TRACK_DISABLE": "true"}):

        @tracker_instance.track
        def f():
            opik_context.update_current_span(name="some-name")
            opik_context.update_current_trace(name="some-name")

        f()
        tracker.flush_tracker()

    assert len(fake_backend.trace_trees) == 0
    assert len(fake_backend.span_trees) == 0
