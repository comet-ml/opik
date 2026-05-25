"""Unit tests for MetricsWorker.

Guards against the bug where each forked RQ child inherits the parent's OTel
MeterProvider + PeriodicExportingMetricReader and emits per-process runtime
metrics under the parent's identical resource attributes, causing Prometheus
to reject the remote-write batch as `duplicate sample for timestamp`.

The fix splits responsibility:

  - `execute_job` (parent) records the per-job counters/histograms after RQ
    returns from the child.
  - `main_work_horse` (forked child) calls `MeterProvider.shutdown()` on the
    inherited provider so the pod has a single metric exporter chain.

These tests verify:

  1. The parent's `execute_job` actually emits `rq_worker.*` metrics on
     success, failure, hard execute_job exception, and that the concurrent
     UpDownCounter balances back to zero.

  2. The child's `main_work_horse` calls shutdown on the current
     MeterProvider and tolerates a shutdown raising an exception (so the
     job still runs).

The actual fork-level behavior (parent state untouched after the child's
shutdown thanks to copy-on-write) is verified end-to-end in a deployed env;
see the test plan in the PR description.
"""
import datetime
from unittest.mock import MagicMock, patch

import pytest

fakeredis = pytest.importorskip("fakeredis")

from opentelemetry import metrics
from opentelemetry.sdk.metrics import MeterProvider
from opentelemetry.sdk.metrics.export import InMemoryMetricReader
from opentelemetry.sdk.resources import Resource


# ---------------------------------------------------------------------------
# Fixtures
#
# OTel Python's `set_meter_provider` is set-once per process, so all tests in
# this file share a single InMemoryMetricReader-backed provider installed at
# session start. Tests stay isolated by using a unique `function` attribute
# per case and filtering data points by it.
# ---------------------------------------------------------------------------


@pytest.fixture(scope="session")
def in_memory_reader():
    """Install an InMemoryMetricReader-backed MeterProvider as the global one
    and return the reader. Lazily fires on first use (no `autouse`) so other
    test files in the same session can install their own provider if needed —
    OTel Python's `set_meter_provider` is set-once and we should not preempt
    other consumers."""
    reader = InMemoryMetricReader()
    provider = MeterProvider(
        resource=Resource.create({"service.name": "opik-python-backend-test"}),
        metric_readers=[reader],
    )
    metrics.set_meter_provider(provider)
    return reader


@pytest.fixture()
def reader(in_memory_reader):
    return in_memory_reader


@pytest.fixture()
def metrics_worker_module():
    """Import the module after the session fixture has installed the real
    provider so its module-level instruments resolve through the proxy to our
    test provider."""
    import opik_backend.workers.metrics_worker as mw
    return mw


# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------


def _utc(second: int = 0) -> datetime.datetime:
    # Anchor in the distant past so `now - created_at` (used by queue_wait_time)
    # is always positive regardless of when the suite runs. The Histogram
    # instrument rejects negative values.
    return datetime.datetime(2020, 1, 1, 0, 0, second, tzinfo=datetime.timezone.utc)


def _make_job(
    func_name: str,
    *,
    created_at: datetime.datetime | None = None,
    started_at: datetime.datetime | None = None,
    ended_at: datetime.datetime | None = None,
    is_failed: bool = False,
    exc_info: str | None = None,
):
    """Build a minimal job-like double.

    A real `rq.job.Job` requires a Redis connection and an explicit `.save()`
    before any attribute access; the worker code only reads attributes and
    calls `.refresh()`, so a constrained MagicMock is the cleanest test
    double here.
    """
    job = MagicMock(spec_set=[
        "id", "func_name", "created_at", "started_at", "ended_at",
        "is_failed", "exc_info", "refresh", "get_status",
    ])
    job.id = f"{func_name}-id"
    job.func_name = func_name
    job.created_at = created_at if created_at is not None else _utc(0)
    job.started_at = started_at
    job.ended_at = ended_at
    job.is_failed = is_failed
    job.exc_info = exc_info
    job.refresh.return_value = None
    job.get_status.return_value = "finished"
    return job


def _make_queue(name: str = "test-queue"):
    queue = MagicMock(spec_set=["name"])
    queue.name = name
    return queue


def _make_worker(metrics_worker_module):
    return metrics_worker_module.MetricsWorker(
        queues=["test-queue"],
        connection=fakeredis.FakeStrictRedis(),
    )


def _datapoints(reader: InMemoryMetricReader, metric_name: str, function: str) -> list:
    """Return all in-memory data points for the given metric, filtered to a
    single test's `function` attribute so tests don't interfere with each
    other."""
    matches = []
    snapshot = reader.get_metrics_data()
    if snapshot is None:
        return matches
    for rm in snapshot.resource_metrics:
        for sm in rm.scope_metrics:
            for m in sm.metrics:
                if m.name != metric_name:
                    continue
                for dp in m.data.data_points:
                    if dp.attributes.get("function") == function:
                        matches.append(dp)
    return matches


# ---------------------------------------------------------------------------
# execute_job (parent) — verifies metric emission
# ---------------------------------------------------------------------------


class TestExecuteJobEmitsFromParent:
    def test_success_records_processed_succeeded_and_durations(
        self, reader, metrics_worker_module
    ):
        func = "test_success_records_processed_succeeded_and_durations"
        job = _make_job(
            func,
            created_at=_utc(0),
            started_at=_utc(2),
            ended_at=_utc(5),
        )
        queue = _make_queue("q-success")
        worker = _make_worker(metrics_worker_module)

        with patch("rq.Worker.execute_job", return_value=True):
            assert worker.execute_job(job, queue) is True

        assert sum(
            dp.value for dp in _datapoints(reader, "rq_worker.jobs.processed", func)
        ) == 1
        assert sum(
            dp.value for dp in _datapoints(reader, "rq_worker.jobs.succeeded", func)
        ) == 1
        assert _datapoints(reader, "rq_worker.jobs.failed", func) == []

        # processing_time = ended_at - started_at = 5s - 2s = 3000ms
        proc_sum = sum(
            dp.sum for dp in _datapoints(reader, "rq_worker.job.processing_time", func)
        )
        assert 2900 <= proc_sum <= 3100, proc_sum

        # total_time = ended_at - created_at = 5s - 0s = 5000ms
        total_sum = sum(
            dp.sum for dp in _datapoints(reader, "rq_worker.job.total_time", func)
        )
        assert 4900 <= total_sum <= 5100, total_sum

        # queue_wait_time recorded once at execute_job entry (~ now - created_at);
        # we only assert the data point exists since `now` varies.
        assert _datapoints(reader, "rq_worker.job.queue_wait_time", func)

    def test_failed_job_records_error_type_parsed_from_exc_info(
        self, reader, metrics_worker_module
    ):
        func = "test_failed_job_records_error_type_parsed_from_exc_info"
        job = _make_job(
            func,
            created_at=_utc(0),
            started_at=_utc(1),
            ended_at=_utc(2),
            is_failed=True,
            exc_info=(
                "Traceback (most recent call last):\n"
                "  File \"x.py\", line 1, in <module>\n"
                "ValueError: bad input"
            ),
        )
        queue = _make_queue("q-failed")
        worker = _make_worker(metrics_worker_module)

        with patch("rq.Worker.execute_job", return_value=False):
            assert worker.execute_job(job, queue) is False

        failed = _datapoints(reader, "rq_worker.jobs.failed", func)
        error_types = {dp.attributes.get("error_type") for dp in failed}
        assert "ValueError" in error_types
        # No spurious success
        assert _datapoints(reader, "rq_worker.jobs.succeeded", func) == []
        # processed counter still increments for failed jobs
        assert sum(
            dp.value for dp in _datapoints(reader, "rq_worker.jobs.processed", func)
        ) == 1
        # concurrent counter still balances back to zero on the failure path
        concurrent = _datapoints(reader, "rq_worker.jobs.concurrent", func)
        assert sum(dp.value for dp in concurrent) == 0

    def test_failed_job_with_multiline_exception_message(
        self, reader, metrics_worker_module
    ):
        """Multi-line exception messages used to be misparsed because the old
        parser took the last non-empty line. The hardened parser scans from
        the end and skips indented continuation lines.
        """
        func = "test_failed_job_with_multiline_exception_message"
        job = _make_job(
            func,
            created_at=_utc(0),
            started_at=_utc(1),
            ended_at=_utc(2),
            is_failed=True,
            exc_info=(
                "Traceback (most recent call last):\n"
                "  File \"x.py\", line 1, in <module>\n"
                "requests.exceptions.ConnectionError: timeout reading body:\n"
                "    Connection reset by peer at offset 1024\n"
                "    while reading chunk 3"
            ),
        )
        queue = _make_queue("q-multiline")
        worker = _make_worker(metrics_worker_module)

        with patch("rq.Worker.execute_job", return_value=False):
            worker.execute_job(job, queue)

        failed = _datapoints(reader, "rq_worker.jobs.failed", func)
        error_types = {dp.attributes.get("error_type") for dp in failed}
        # Dotted module prefix stripped to the leaf class name.
        assert error_types == {"ConnectionError"}, error_types

    def test_hard_execute_job_exception_records_failed_with_exception_class(
        self, reader, metrics_worker_module
    ):
        func = "test_hard_execute_job_exception_records_failed_with_exception_class"
        job = _make_job(
            func,
            created_at=_utc(0),
            started_at=_utc(1),
            ended_at=_utc(1),
        )
        queue = _make_queue("q-hard")
        worker = _make_worker(metrics_worker_module)

        class BoomError(RuntimeError):
            pass

        with patch("rq.Worker.execute_job", side_effect=BoomError("boom")):
            with pytest.raises(BoomError):
                worker.execute_job(job, queue)

        failed = _datapoints(reader, "rq_worker.jobs.failed", func)
        error_types = {dp.attributes.get("error_type") for dp in failed}
        assert "BoomError" in error_types
        # finally-block still records processed and decrements the concurrent
        # counter when super().execute_job raises.
        assert sum(
            dp.value for dp in _datapoints(reader, "rq_worker.jobs.processed", func)
        ) == 1
        concurrent = _datapoints(reader, "rq_worker.jobs.concurrent", func)
        assert sum(dp.value for dp in concurrent) == 0

    def test_refresh_failure_emits_explicit_unknown_outcome(
        self, reader, metrics_worker_module
    ):
        """If `job.refresh()` raises (e.g., Redis outage, NoSuchJobError), we
        still record `rq_worker.jobs.processed` and an explicit failure with
        `error_type="RefreshFailed"` so the terminal metric isn't silently
        dropped. We also must NOT consult `job.is_failed` (which in RQ
        triggers another Redis round-trip and could itself raise).
        """
        func = "test_refresh_failure_emits_explicit_unknown_outcome"
        job = _make_job(func, created_at=_utc(0))
        # Refresh fails AND any subsequent Redis-dependent read would fail too
        # — if the worker calls `is_failed`/`get_status` after a failed
        # refresh, the test will surface that as an unhandled exception.
        job.refresh.side_effect = RuntimeError("Redis unavailable")
        type(job).is_failed = property(
            lambda _: pytest.fail("is_failed must not be consulted after refresh failure")
        )

        queue = _make_queue("q-refresh-fail")
        worker = _make_worker(metrics_worker_module)

        with patch("rq.Worker.execute_job", return_value=True):
            assert worker.execute_job(job, queue) is True

        assert sum(
            dp.value for dp in _datapoints(reader, "rq_worker.jobs.processed", func)
        ) == 1
        failed = _datapoints(reader, "rq_worker.jobs.failed", func)
        assert {dp.attributes.get("error_type") for dp in failed} == {"RefreshFailed"}
        # No success was recorded
        assert _datapoints(reader, "rq_worker.jobs.succeeded", func) == []
        # No bogus durations recorded with stale/None timestamps
        assert _datapoints(reader, "rq_worker.job.processing_time", func) == []
        assert _datapoints(reader, "rq_worker.job.total_time", func) == []
        assert _datapoints(reader, "rq_worker.job.queue_wait_time", func) == []
        # Concurrent counter still balances
        concurrent = _datapoints(reader, "rq_worker.jobs.concurrent", func)
        assert sum(dp.value for dp in concurrent) == 0

    def test_concurrent_counter_balances_to_zero_after_a_single_job(
        self, reader, metrics_worker_module
    ):
        func = "test_concurrent_counter_balances_to_zero_after_a_single_job"
        job = _make_job(
            func,
            created_at=_utc(0),
            started_at=_utc(1),
            ended_at=_utc(2),
        )
        queue = _make_queue("q-concurrent")
        worker = _make_worker(metrics_worker_module)

        with patch("rq.Worker.execute_job", return_value=True):
            worker.execute_job(job, queue)

        # UpDownCounter exports its cumulative state. After exactly one +1 and
        # one -1 for this function attribute, the sum must be zero.
        concurrent = _datapoints(reader, "rq_worker.jobs.concurrent", func)
        assert concurrent, "concurrent counter should have at least one data point"
        assert sum(dp.value for dp in concurrent) == 0


# ---------------------------------------------------------------------------
# main_work_horse (forked child) — verifies MeterProvider shutdown
# ---------------------------------------------------------------------------


class TestMainWorkHorseSilencesChild:
    """The child's inherited MeterProvider must be shut down so the pod has
    a single exporter chain. We monkeypatch `metrics.get_meter_provider` for
    these tests so the real session-wide provider used by the execute_job
    tests above stays intact."""

    def test_shutdown_is_called_then_super_main_work_horse_runs(
        self, metrics_worker_module, monkeypatch
    ):
        local_provider = MagicMock(spec=["shutdown"])
        monkeypatch.setattr(metrics_worker_module.metrics, "get_meter_provider",
                            lambda: local_provider)

        worker = _make_worker(metrics_worker_module)
        with patch("rq.Worker.main_work_horse", return_value=None) as super_main:
            worker.main_work_horse(_make_job("mwh-success"), _make_queue())

        local_provider.shutdown.assert_called_once()
        super_main.assert_called_once()

    def test_shutdown_exception_is_swallowed_and_super_still_runs(
        self, metrics_worker_module, monkeypatch
    ):
        local_provider = MagicMock(spec=["shutdown"])
        local_provider.shutdown.side_effect = RuntimeError("already shutdown")
        monkeypatch.setattr(metrics_worker_module.metrics, "get_meter_provider",
                            lambda: local_provider)

        worker = _make_worker(metrics_worker_module)
        with patch("rq.Worker.main_work_horse", return_value=None) as super_main:
            worker.main_work_horse(_make_job("mwh-shutdown-raises"), _make_queue())

        # The shutdown attempt must actually happen — otherwise this test
        # would still pass if the child skipped shutdown entirely.
        local_provider.shutdown.assert_called_once()
        super_main.assert_called_once()
