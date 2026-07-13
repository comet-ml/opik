"""Unit tests for optimization_lifecycle status manager (W19 / OPIK-7029).

Verifies that a failure in mark_completed() does NOT cause mark_error() to be
called — a successfully-finished run must never be flipped to ERROR by a
transient completion-callback failure. If mark_completed raises, the run stays
RUNNING and the backend stalled-run reaper handles it (OPIK-7159 backstop).
"""

from unittest.mock import MagicMock, call

import pytest

from opik_backend.studio.status_manager import (
    OptimizationStatusManager,
    optimization_lifecycle,
)


def _make_status_manager(**overrides) -> OptimizationStatusManager:
    """Return a fully-mocked OptimizationStatusManager.

    All public methods are replaced with MagicMocks so callers can assert
    call counts / order without touching the Opik REST client.
    """
    sm = MagicMock(spec=OptimizationStatusManager)
    for attr, value in overrides.items():
        setattr(sm, attr, value)
    return sm


class TestOptimizationLifecycleSuccess:
    """Happy path — no exceptions raised anywhere."""

    def test_mark_running_then_mark_completed_on_success(self):
        sm = _make_status_manager()

        with optimization_lifecycle(sm):
            pass  # body succeeds

        sm.mark_running.assert_called_once()
        sm.mark_completed.assert_called_once()
        sm.mark_error.assert_not_called()

    def test_close_always_called_on_success(self):
        sm = _make_status_manager()

        with optimization_lifecycle(sm):
            pass

        sm.close.assert_called_once()

    def test_status_manager_yielded_to_body(self):
        sm = _make_status_manager()
        received = []

        with optimization_lifecycle(sm) as yielded:
            received.append(yielded)

        assert received == [sm]

    def test_running_called_before_completed(self):
        """Ensure ordering: mark_running → body → mark_completed."""
        call_order = []
        sm = _make_status_manager()
        sm.mark_running.side_effect = lambda: call_order.append("running")
        sm.mark_completed.side_effect = lambda: call_order.append("completed")

        with optimization_lifecycle(sm):
            call_order.append("body")

        assert call_order == ["running", "body", "completed"]


class TestOptimizationLifecycleBodyFailure:
    """Body raises — must mark_error and re-raise."""

    def test_mark_error_called_when_body_raises(self):
        sm = _make_status_manager()
        exc = RuntimeError("boom")

        with pytest.raises(RuntimeError, match="boom"):
            with optimization_lifecycle(sm):
                raise exc

        sm.mark_error.assert_called_once()

    def test_mark_completed_not_called_when_body_raises(self):
        sm = _make_status_manager()

        with pytest.raises(ValueError):
            with optimization_lifecycle(sm):
                raise ValueError("bad input")

        sm.mark_completed.assert_not_called()

    def test_original_exception_re_raised(self):
        sm = _make_status_manager()
        original = KeyError("missing key")

        with pytest.raises(KeyError) as exc_info:
            with optimization_lifecycle(sm):
                raise original

        assert exc_info.value is original

    def test_close_always_called_when_body_raises(self):
        sm = _make_status_manager()

        with pytest.raises(RuntimeError):
            with optimization_lifecycle(sm):
                raise RuntimeError("fail")

        sm.close.assert_called_once()


class TestOptimizationLifecycleMarkCompletedFails:
    """W19: mark_completed raises — must NOT call mark_error.

    A transient network blip or Opik-key expiry during the completion callback
    must not flip a successfully-finished run to ERROR. The run stays RUNNING
    and the backend stalled-run reaper (OPIK-7159) handles it eventually.
    """

    def test_mark_error_not_called_when_mark_completed_raises(self):
        sm = _make_status_manager()
        sm.mark_completed.side_effect = ConnectionError("Opik backend unreachable")

        # The context manager itself must not propagate the mark_completed error
        # up through the with-block (it is swallowed and logged).
        with optimization_lifecycle(sm):
            pass  # body succeeds

        sm.mark_error.assert_not_called()

    def test_mark_completed_failure_does_not_propagate(self):
        """A failed completion callback must not raise out of the context manager."""
        sm = _make_status_manager()
        sm.mark_completed.side_effect = OSError("network gone")

        # Should not raise
        with optimization_lifecycle(sm):
            pass

    def test_close_still_called_when_mark_completed_raises(self):
        sm = _make_status_manager()
        sm.mark_completed.side_effect = TimeoutError("timed out")

        with optimization_lifecycle(sm):
            pass

        sm.close.assert_called_once()

    def test_mark_running_called_even_if_mark_completed_later_raises(self):
        sm = _make_status_manager()
        sm.mark_completed.side_effect = RuntimeError("failed to mark completed")

        with optimization_lifecycle(sm):
            pass

        sm.mark_running.assert_called_once()

    def test_mark_completed_attempted_even_if_it_will_raise(self):
        """mark_completed is still *called* — it just doesn't flip to ERROR when it fails."""
        sm = _make_status_manager()
        sm.mark_completed.side_effect = ConnectionError("unreachable")

        with optimization_lifecycle(sm):
            pass

        sm.mark_completed.assert_called_once()


class TestOptimizationLifecycleMarkRunningFails:
    """mark_running raises — edge case: body never runs."""

    def test_mark_error_called_when_mark_running_raises(self):
        sm = _make_status_manager()
        sm.mark_running.side_effect = ConnectionError("can't reach backend")

        with pytest.raises(ConnectionError):
            with optimization_lifecycle(sm):
                pass  # pragma: no cover

        sm.mark_error.assert_called_once()

    def test_mark_completed_not_called_when_mark_running_raises(self):
        sm = _make_status_manager()
        sm.mark_running.side_effect = RuntimeError("no connection")

        with pytest.raises(RuntimeError):
            with optimization_lifecycle(sm):
                pass  # pragma: no cover

        sm.mark_completed.assert_not_called()

    def test_close_called_when_mark_running_raises(self):
        sm = _make_status_manager()
        sm.mark_running.side_effect = IOError("unreachable")

        with pytest.raises(IOError):
            with optimization_lifecycle(sm):
                pass  # pragma: no cover

        sm.close.assert_called_once()
