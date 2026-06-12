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
from unittest.mock import patch

import pytest

from opik_backend import create_app
from opik_backend.executor import SATURATED_ERROR, SHUTDOWN_ERROR, CodeExecutorBase
from opik_backend.executor_process import ProcessExecutor

EVALUATORS_URL = "/v1/private/evaluators/python"
ENV_VAR = "PYTHON_CODE_EXECUTOR_POOL_ACQUIRE_TIMEOUT_IN_SECS"
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


@pytest.mark.parametrize("set_stop_event, expected_match", [
    pytest.param(False, "pool exhausted", id="empty_pool"),
    pytest.param(True,  "shutting down",  id="shutdown"),
])
def test_get_worker_raises_timeout_error(empty_pool_executor, set_stop_event, expected_match):
    if set_stop_event:
        empty_pool_executor.stop_event.set()

    with pytest.raises(TimeoutError, match=expected_match):
        empty_pool_executor.get_worker()


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
