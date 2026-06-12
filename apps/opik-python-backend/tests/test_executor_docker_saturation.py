"""Saturation/backpressure behavior of DockerExecutor.

Covers the public contract:
- pool acquisition fails fast and surfaces as HTTP 503 with the shared
  ``SATURATED_ERROR`` body
- ``get_container`` raises stdlib :class:`TimeoutError` on saturation and on
  executor shutdown
- the saturation outcome is recorded via the existing
  ``execution_outcome_counter`` metric

The Docker daemon is not required: ``docker.from_env`` is mocked, the pool
monitor scheduler is stubbed, and ``_pre_warm_container_pool`` is patched
out so no real containers are created.
"""
import logging
from unittest.mock import MagicMock, patch

import pytest

from opik_backend import create_app
from opik_backend.executor import SATURATED_ERROR, SHUTDOWN_ERROR
from opik_backend.executor_docker import DockerExecutor

EVALUATORS_URL = "/v1/private/evaluators/python"
DATA = {"output": "x", "reference": "x"}


@pytest.fixture
def empty_pool_executor():
    """DockerExecutor whose container_pool is empty, with the Docker daemon mocked.

    Only the saturation surface (``get_container`` + ``run_scoring``) is exercised,
    so the Docker client, pool pre-warming, and pool-monitor scheduler are stubbed.
    The in-memory ``container_pool`` queue is left empty to simulate saturation.
    """
    with (
        patch("opik_backend.executor_docker.docker.from_env", return_value=MagicMock()),
        patch("opik_backend.executor_docker.DockerExecutor._pre_warm_container_pool"),
        patch("opik_backend.executor_docker.DockerExecutor._start_pool_monitor"),
    ):
        executor = DockerExecutor()
        yield executor
        executor.stop_event.set()


@pytest.mark.parametrize("set_stop_event, expected_match", [
    pytest.param(False, "pool exhausted", id="empty_pool"),
    pytest.param(True,  "shutting down",  id="shutdown"),
])
def test_get_container_raises_timeout_error(empty_pool_executor, set_stop_event, expected_match):
    if set_stop_event:
        empty_pool_executor.stop_event.set()

    with pytest.raises(TimeoutError, match=expected_match):
        empty_pool_executor.get_container()


def test_get_container_logs_warning_on_saturation(empty_pool_executor, caplog):
    """Saturation is the third leg of the observability triangle (gauge,
    counter, log). Without the WARNING, ops loses the real-time signal."""
    with caplog.at_level(logging.WARNING):
        with pytest.raises(TimeoutError):
            empty_pool_executor.get_container()

    assert any(
        "pool exhausted" in r.message
        for r in caplog.records
        if r.levelno == logging.WARNING
    )


def test_get_container_refreshes_gauge_on_saturation(empty_pool_executor):
    """The Empty branch refreshes the pool-size gauge so the saturation event
    reports the zero-available state instead of the pre-call snapshot."""
    with patch.object(empty_pool_executor, "_update_container_pool_size_metric") as update:
        with pytest.raises(TimeoutError):
            empty_pool_executor.get_container()

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
    """If stop_event fires between get_container's pre-check and the bounded
    Queue.get, the resulting TimeoutError should surface as shutdown, not as
    pool saturation — and must not tick the saturated outcome counter."""

    def stop_then_raise():
        empty_pool_executor.stop_event.set()
        raise TimeoutError("Container pool exhausted: simulated race")

    with patch.object(empty_pool_executor, "get_container", side_effect=stop_then_raise):
        with patch.object(empty_pool_executor, "_record_execution_outcome") as record:
            response = empty_pool_executor.run_scoring(code="<unused>", data=DATA)

    assert response == {"code": 503, "error": SHUTDOWN_ERROR}
    assert all(call.args[0] != "saturated" for call in record.call_args_list)


@pytest.mark.parametrize("payload_type", [None, "trace", "trace_thread"])
def test_run_scoring_records_saturated_outcome(empty_pool_executor, payload_type):
    with patch.object(empty_pool_executor, "_record_execution_outcome") as record:
        empty_pool_executor.run_scoring(code="<unused>", data=DATA, payload_type=payload_type)

    record.assert_any_call("saturated", payload_type)


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
