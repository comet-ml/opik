"""Unit tests for optimization_lifecycle status manager (W19 / OPIK-7029).

Verifies that a failure in mark_completed() does NOT cause mark_error() to be
called — a successfully-finished run must never be flipped to ERROR by a
transient completion-callback failure. If mark_completed raises, the run stays
RUNNING and the backend stalled-run reaper handles it (OPIK-7159 backstop).

Also verifies scoring_health forwarding (W18-C / OPIK-7043):
- When the SDK result carries details["scoring_health"], the completion payload
  includes metadata.scoring_health.
- When absent or malformed, no metadata key is sent and the completion path
  never raises.
"""

from unittest.mock import MagicMock, patch

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


# ---------------------------------------------------------------------------
# Scoring-health / metadata forwarding (W18-C / OPIK-7043)
# ---------------------------------------------------------------------------


def _make_real_status_manager() -> OptimizationStatusManager:
    """Return a real OptimizationStatusManager with a mocked Opik client.

    Unlike _make_status_manager() (which mocks the whole object), this uses
    the actual class so we can test real method logic such as
    set_completion_metadata / mark_completed / update_status.
    """
    mock_client = MagicMock()
    return OptimizationStatusManager(
        client=mock_client, optimization_id="test-opt-id-123"
    )


class TestScoringHealthForwarding:
    """set_completion_metadata queues scoring_health; mark_completed forwards it."""

    def test_mark_completed_includes_metadata_when_scoring_health_set(self):
        """When scoring_health is queued, mark_completed passes it to update_status."""
        sm = _make_real_status_manager()
        scoring_health = {"failed_count": 3, "total_count": 10}
        sm.set_completion_metadata({"scoring_health": scoring_health})

        with patch.object(sm, "update_status") as mock_update:
            sm.mark_completed()

        mock_update.assert_called_once_with(
            "completed",
            metadata={"scoring_health": {"failed_count": 3, "total_count": 10}},
        )

    def test_mark_completed_omits_metadata_when_none_queued(self):
        """When no metadata was queued, mark_completed calls update_status without metadata."""
        sm = _make_real_status_manager()

        with patch.object(sm, "update_status") as mock_update:
            sm.mark_completed()

        mock_update.assert_called_once_with("completed", metadata=None)

    def test_pending_metadata_cleared_after_mark_completed(self):
        """_pending_metadata is reset to None after mark_completed consumes it."""
        sm = _make_real_status_manager()
        sm.set_completion_metadata(
            {"scoring_health": {"failed_count": 1, "total_count": 5}}
        )

        with patch.object(sm, "update_status"):
            sm.mark_completed()

        assert sm._pending_metadata is None

    def test_explicit_metadata_arg_takes_precedence_over_pending(self):
        """An explicit metadata kwarg to mark_completed overrides _pending_metadata."""
        sm = _make_real_status_manager()
        sm.set_completion_metadata(
            {"scoring_health": {"failed_count": 0, "total_count": 5}}
        )
        explicit_meta = {"scoring_health": {"failed_count": 99, "total_count": 99}}

        with patch.object(sm, "update_status") as mock_update:
            sm.mark_completed(metadata=explicit_meta)

        mock_update.assert_called_once_with("completed", metadata=explicit_meta)

    def test_update_status_uses_typed_client_when_no_metadata(self):
        """update_status uses the typed SDK client when no metadata is given."""
        sm = _make_real_status_manager()
        sm.update_status("running")

        sm.client.rest_client.optimizations.update_optimizations_by_id.assert_called_once_with(
            "test-opt-id-123",
            status="running",
            request_options={"max_retries": 3},
        )

    def test_update_status_uses_raw_http_client_when_metadata_given(self):
        """update_status falls through to the underlying HTTP client when metadata is provided."""
        sm = _make_real_status_manager()
        scoring_health = {"failed_count": 2, "total_count": 8}

        # A 2xx response means the raw PUT succeeded — no fallback should fire.
        raw_client = sm.client.rest_client.optimizations._raw_client
        http_client = raw_client._client_wrapper.httpx_client
        http_client.request.return_value.status_code = 200

        sm.update_status("completed", metadata={"scoring_health": scoring_health})

        # The typed client must NOT be called when the metadata path succeeds.
        sm.client.rest_client.optimizations.update_optimizations_by_id.assert_not_called()

        # The underlying HTTP client IS called with the full payload.
        http_client.request.assert_called_once_with(
            "v1/private/optimizations/test-opt-id-123",
            method="PUT",
            json={
                "status": "completed",
                "metadata": {"scoring_health": scoring_health},
            },
            headers={"content-type": "application/json"},
            request_options={"max_retries": 3},
        )

    def test_metadata_put_rejected_falls_back_to_typed_status_only(self):
        """A non-2xx on the metadata PUT falls back to the typed status-only
        update so the run still transitions (scoring_health is best-effort)."""
        sm = _make_real_status_manager()
        raw_client = sm.client.rest_client.optimizations._raw_client
        http_client = raw_client._client_wrapper.httpx_client
        http_client.request.return_value.status_code = 400

        sm.update_status(
            "completed",
            metadata={"scoring_health": {"failed_count": 1, "total_count": 1}},
        )

        http_client.request.assert_called_once()
        # Fallback: the status still lands via the typed client, no metadata.
        sm.client.rest_client.optimizations.update_optimizations_by_id.assert_called_once_with(
            "test-opt-id-123",
            status="completed",
            request_options={"max_retries": 3},
        )

    def test_metadata_put_raising_falls_back_to_typed_status_only(self):
        """A transport-level failure on the metadata PUT also falls back so the
        completion never gets stuck behind the scoring_health nicety."""
        sm = _make_real_status_manager()
        raw_client = sm.client.rest_client.optimizations._raw_client
        http_client = raw_client._client_wrapper.httpx_client
        http_client.request.side_effect = RuntimeError("connection dropped")

        sm.update_status(
            "completed",
            metadata={"scoring_health": {"failed_count": 1, "total_count": 2}},
        )

        sm.client.rest_client.optimizations.update_optimizations_by_id.assert_called_once_with(
            "test-opt-id-123",
            status="completed",
            request_options={"max_retries": 3},
        )

    def test_empty_dict_metadata_still_uses_raw_http_client(self):
        """`{}` is metadata "present" (is not None) and must go through the raw PUT
        path, not be silently dropped to the typed status-only call."""
        sm = _make_real_status_manager()
        raw_client = sm.client.rest_client.optimizations._raw_client
        http_client = raw_client._client_wrapper.httpx_client
        http_client.request.return_value.status_code = 200

        sm.update_status("completed", metadata={})

        sm.client.rest_client.optimizations.update_optimizations_by_id.assert_not_called()
        http_client.request.assert_called_once()

    def test_set_completion_metadata_none_leaves_no_pending(self):
        """Explicitly passing None to set_completion_metadata results in no metadata sent."""
        sm = _make_real_status_manager()
        sm.set_completion_metadata(None)

        with patch.object(sm, "update_status") as mock_update:
            sm.mark_completed()

        mock_update.assert_called_once_with("completed", metadata=None)


class TestScoringHealthGuard:
    """The completion path must never raise due to a missing/malformed scoring_health."""

    def test_lifecycle_succeeds_without_scoring_health_queued(self):
        """optimization_lifecycle still marks completed when no metadata is queued."""
        sm = _make_status_manager()

        with optimization_lifecycle(sm):
            pass  # body succeeds, no set_completion_metadata called

        sm.mark_completed.assert_called_once()

    def test_lifecycle_succeeds_with_scoring_health_queued_via_real_manager(self):
        """End-to-end: the lifecycle's mark_completed carries the queued metadata."""
        sm = _make_real_status_manager()
        sm.set_completion_metadata(
            {"scoring_health": {"failed_count": 1, "total_count": 4}}
        )

        with (
            patch.object(sm, "mark_running"),
            patch.object(sm, "close"),
            patch.object(sm, "update_status") as mock_update,
            patch.object(sm, "mark_error"),
        ):
            sm.mark_completed()

        mock_update.assert_called_once_with(
            "completed",
            metadata={"scoring_health": {"failed_count": 1, "total_count": 4}},
        )
