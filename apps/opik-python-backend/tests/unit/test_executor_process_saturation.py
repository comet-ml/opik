"""Saturation/backpressure behavior of ProcessExecutor.

Covers the public contract:
- pool acquisition fails fast and surfaces as HTTP 503 with the shared
  ``SATURATED_ERROR`` body
- ``get_worker`` raises stdlib :class:`TimeoutError` on saturation and on
  executor shutdown
- the acquire timeout is configurable via env var and falls back to 0
  for any invalid input
- the Flask route translates saturation to HTTP 503
"""
import logging
from queue import Empty
from unittest.mock import MagicMock, patch

import pytest

from opik_backend import create_app
from opik_backend.executor import (
    CodeExecutorBase,
    EXEC_TIMEOUT_ERROR,
    POOL_ACQUIRE_TIMEOUT_ENV_VAR,
    SATURATED_ERROR,
    SHUTDOWN_ERROR,
)
from opik_backend.executor_process import ProcessExecutor

EVALUATORS_URL = "/v1/private/evaluators/python"
ENV_VAR = POOL_ACQUIRE_TIMEOUT_ENV_VAR
DATA = {"output": "x", "reference": "x"}


@pytest.fixture
def empty_pool_executor():
    """ProcessExecutor with an empty process_pool and no started services.

    ``start_services()`` is intentionally not called: the saturation path lives
    entirely in the in-memory pool, so we exercise it without spinning up real
    subprocesses.
    """
    executor = ProcessExecutor()
    yield executor


@pytest.mark.parametrize("set_stop_event, expected_message", [
    pytest.param(False, SATURATED_ERROR, id="empty_pool"),
    pytest.param(True,  SHUTDOWN_ERROR,  id="shutdown"),
])
def test_get_worker_raises_timeout_error(empty_pool_executor, set_stop_event, expected_message):
    """The exception text is one of the two wire-facing constants; internal
    config (pool_acquire_timeout, max_parallel) stays in the log only."""
    if set_stop_event:
        empty_pool_executor.stop_event.set()

    with pytest.raises(TimeoutError) as excinfo:
        empty_pool_executor.get_worker()

    assert str(excinfo.value) == expected_message


def test_get_worker_preserves_empty_cause_on_saturation(empty_pool_executor):
    """``raise TimeoutError(...) from e`` keeps the underlying
    :class:`queue.Empty` as ``__cause__`` so tracebacks still link the
    saturation TimeoutError to its originating queue event for debugging."""
    with pytest.raises(TimeoutError) as excinfo:
        empty_pool_executor.get_worker()

    assert isinstance(excinfo.value.__cause__, Empty)


def test_get_worker_logs_warning_on_saturation(empty_pool_executor, caplog):
    """Saturation is the third leg of the observability triangle (gauge,
    counter, log). Without the WARNING, ops loses the real-time signal."""
    with caplog.at_level(logging.WARNING):
        with pytest.raises(TimeoutError):
            empty_pool_executor.get_worker()

    assert any(
        "pool exhausted" in r.message
        for r in caplog.records
        if r.levelno == logging.WARNING
    )


def test_get_worker_logs_warning_on_dead_worker(empty_pool_executor, caplog):
    """The dead-worker WARNING is the operator-facing signal that
    distinguishes 'workers dying' from 'all workers busy' — both surface
    as 503 + SATURATED_ERROR, only the log tells the difference."""
    dead_process = MagicMock()
    dead_process.is_alive.return_value = False
    dead_worker = {"id": "dead-x", "process": dead_process, "connection": MagicMock()}

    with caplog.at_level(logging.WARNING):
        with patch.object(empty_pool_executor.process_pool, "get", return_value=dead_worker):
            with patch.object(empty_pool_executor, "_async_terminate"):
                with pytest.raises(TimeoutError):
                    empty_pool_executor.get_worker()

    assert any(
        "Dead worker" in r.message and "dead-x" in r.message
        for r in caplog.records
        if r.levelno == logging.WARNING
    )


def test_get_worker_treats_dead_worker_as_saturation(empty_pool_executor):
    """A dead worker retrieved from the pool is async-terminated and
    surfaces as TimeoutError with the wire-facing saturation constant —
    internal worker state stays in the log, not in anything a downstream
    ``str(exc)`` could observe."""
    dead_process = MagicMock()
    dead_process.is_alive.return_value = False
    dead_worker = {"id": "dead", "process": dead_process, "connection": MagicMock()}

    with patch.object(empty_pool_executor.process_pool, "get", return_value=dead_worker):
        with patch.object(empty_pool_executor, "_async_terminate") as async_term:
            with pytest.raises(TimeoutError) as excinfo:
                empty_pool_executor.get_worker()

    async_term.assert_called_once_with(dead_worker)
    assert str(excinfo.value) == SATURATED_ERROR


def test_run_scoring_returns_503_when_pool_yields_dead_worker(empty_pool_executor):
    """End-to-end: a dead worker collapses to the same 503 + SATURATED_ERROR
    body as real pool saturation. Body intentionally re-used — the wire
    contract is the HTTP status, the dead-worker case is distinguishable
    via logs."""
    dead_process = MagicMock()
    dead_process.is_alive.return_value = False
    dead_worker = {"id": "dead", "process": dead_process, "connection": MagicMock()}

    with patch.object(empty_pool_executor.process_pool, "get", return_value=dead_worker):
        with patch.object(empty_pool_executor, "_async_terminate"):
            response = empty_pool_executor.run_scoring(code="<unused>", data=DATA)

    assert response == {"code": 503, "error": SATURATED_ERROR}


def test_run_scoring_async_terminates_on_exec_timeout(empty_pool_executor):
    """The exec-timeout branch must terminate the unresponsive worker off
    the request thread, and return 504 with the shared exec-timeout body
    so Java BE's retry policy treats this uniformly with the Docker
    executor (504 is retryable; 500 is not)."""
    process = MagicMock()
    process.is_alive.return_value = True
    connection = MagicMock()
    connection.poll.return_value = False  # exec_timeout elapses with no result
    worker = {"id": "w-timeout", "process": process, "connection": connection}

    with patch.object(empty_pool_executor, "get_worker", return_value=worker):
        with patch.object(empty_pool_executor, "_async_terminate") as async_term:
            response = empty_pool_executor.run_scoring(code="<unused>", data=DATA)

    async_term.assert_called_once_with(worker)
    assert response == {"code": 504, "error": EXEC_TIMEOUT_ERROR}


def test_run_scoring_async_terminates_on_exception(empty_pool_executor):
    """The generic exception branch must also terminate the failed worker
    off the request thread, symmetric to the exec-timeout branch."""
    process = MagicMock()
    process.is_alive.return_value = True
    # connection=None makes the inner ``if not connection: raise`` fire,
    # which is the simplest way to land us in the generic except branch.
    worker = {"id": "w-error", "process": process, "connection": None}

    with patch.object(empty_pool_executor, "get_worker", return_value=worker):
        with patch.object(empty_pool_executor, "_async_terminate") as async_term:
            response = empty_pool_executor.run_scoring(code="<unused>", data=DATA)

    async_term.assert_called_once_with(worker)
    assert response["code"] == 500


def test_async_terminate_dispatches_via_releaser_executor_when_available(empty_pool_executor):
    """When start_services has wired up the releaser pool, termination must
    happen off the request thread."""
    worker = {"id": "any", "process": MagicMock(), "connection": MagicMock()}
    fake_releaser = MagicMock()
    empty_pool_executor.releaser_executor = fake_releaser

    from opik_backend.executor_process import terminate_worker
    empty_pool_executor._async_terminate(worker)

    fake_releaser.submit.assert_called_once_with(terminate_worker, worker)


def test_async_terminate_falls_back_to_inline_when_releaser_unavailable(empty_pool_executor):
    """Without a releaser pool (pre-start_services / tests), termination
    runs inline — acceptable since no request thread is on the line."""
    worker = {"id": "any", "process": MagicMock(), "connection": MagicMock()}
    assert empty_pool_executor.releaser_executor is None

    with patch("opik_backend.executor_process.terminate_worker") as terminate:
        empty_pool_executor._async_terminate(worker)

    terminate.assert_called_once_with(worker)


def test_get_worker_refreshes_gauge_on_saturation(empty_pool_executor):
    """The Empty branch refreshes the pool-size gauge so the saturation event
    reports the zero-available state instead of the pre-call snapshot."""
    with patch.object(empty_pool_executor, "_update_pool_size_metric") as update:
        with pytest.raises(TimeoutError):
            empty_pool_executor.get_worker()

    # Pre-call update + Empty-branch update; dropping the latter regresses
    # to a single call and would silently leave the gauge stale on saturation.
    assert update.call_count == 2


def test_run_scoring_returns_503_with_pool_saturated_message(empty_pool_executor):
    response = empty_pool_executor.run_scoring(code="<unused>", data=DATA)

    assert response == {"code": 503, "error": SATURATED_ERROR}


def test_run_scoring_returns_shutdown_body_when_stopping(empty_pool_executor):
    """503 on shutdown uses a distinct body from the saturation body so the
    two paths remain diagnosable in monitoring."""
    empty_pool_executor.stop_event.set()

    response = empty_pool_executor.run_scoring(code="<unused>", data=DATA)

    assert response == {"code": 503, "error": SHUTDOWN_ERROR}
    assert response["error"] != SATURATED_ERROR


def test_run_scoring_returns_shutdown_body_when_stop_event_wins_race(empty_pool_executor):
    """If a SIGTERM/SIGINT fires during the bounded Queue.get inside
    get_worker, the resulting TimeoutError should surface as shutdown, not
    as pool saturation."""

    def stop_then_raise():
        empty_pool_executor.stop_event.set()
        raise TimeoutError("Process pool exhausted: simulated race")

    with patch.object(empty_pool_executor, "get_worker", side_effect=stop_then_raise):
        response = empty_pool_executor.run_scoring(code="<unused>", data=DATA)

    assert response == {"code": 503, "error": SHUTDOWN_ERROR}


@pytest.mark.parametrize("raw, expected", [
    ("0",    0.0),
    ("0.25", 0.25),
    ("1",    1.0),
    ("5",    5.0),
])
def test_parse_pool_acquire_timeout_accepts_finite_non_negative(monkeypatch, raw, expected):
    monkeypatch.setenv(ENV_VAR, raw)

    assert CodeExecutorBase._parse_pool_acquire_timeout() == expected


@pytest.mark.parametrize("raw", [
    "not-a-number", "",        # non-numeric
    "-1", "-0.5",              # negative
    "inf", "-inf", "nan",      # non-finite — would re-arm the unbounded wait
])
def test_parse_pool_acquire_timeout_falls_back_to_zero_on_invalid(monkeypatch, raw):
    monkeypatch.setenv(ENV_VAR, raw)

    assert CodeExecutorBase._parse_pool_acquire_timeout() == 0.0


def test_parse_pool_acquire_timeout_defaults_to_zero(monkeypatch):
    monkeypatch.delenv(ENV_VAR, raising=False)

    assert CodeExecutorBase._parse_pool_acquire_timeout() == 0.0


def test_executor_applies_parsed_pool_acquire_timeout(monkeypatch):
    """__init__ wires the parsed timeout onto the instance field."""
    monkeypatch.setenv(ENV_VAR, "0.5")

    executor = ProcessExecutor()

    assert executor.pool_acquire_timeout == 0.5


def test_route_returns_503_when_pool_saturated(empty_pool_executor):
    app = create_app(should_init_executor=False)
    app.executor = empty_pool_executor
    client = app.test_client()

    response = client.post(
        EVALUATORS_URL,
        json={"code": "<unused>", "data": DATA},
    )

    assert response.status_code == 503
    assert SATURATED_ERROR in response.json["error"]
