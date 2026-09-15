"""Execution-outcome telemetry of DockerExecutor.run_scoring.

The outcome recorded on the ``execution_outcome_counter`` metric must match what
the caller is actually handed. A metric can exit 0 and still fail to produce a
usable result line — parse_execution_result reports that as a 4xx and the HTTP
layer aborts with it — so keying the outcome on the exit code alone counted those
runs as successes.

The Docker daemon is not required: ``docker.from_env`` is mocked, pool pre-warming
and the pool monitor are stubbed, and a container whose ``exec_run`` returns a
canned result is injected into the pool.
"""
import json
from unittest.mock import MagicMock, patch

import pytest

from opik_backend.executor_docker import DockerExecutor

DATA = {"output": "x", "reference": "x"}


@pytest.fixture
def executor():
    with (
        patch("opik_backend.executor_docker.docker.from_env", return_value=MagicMock()),
        patch("opik_backend.executor_docker.DockerExecutor._pre_warm_container_pool"),
        patch("opik_backend.executor_docker.DockerExecutor._start_pool_monitor"),
    ):
        instance = DockerExecutor()
        yield instance
        instance.stop_event.set()


def run_with_result(executor, exit_code, output: bytes):
    """Run scoring against a container returning a canned exec result."""
    container = MagicMock()
    container.exec_run.return_value = MagicMock(exit_code=exit_code, output=output)
    with patch.object(executor, "get_container", return_value=container):
        with patch.object(executor, "_record_execution_outcome") as record:
            response = executor.run_scoring(code="<unused>", data=DATA)
    return response, [call.args[0] for call in record.call_args_list]


def test_scores_payload_records_success(executor):
    payload = {"scores": [{"name": "m", "value": 1.0}]}

    response, outcomes = run_with_result(executor, 0, json.dumps(payload).encode("utf-8"))

    assert response == payload
    assert outcomes == ["success"]


def test_exit_zero_without_output_is_not_recorded_as_success(executor):
    response, outcomes = run_with_result(executor, 0, b"")

    assert response["code"] == 400
    assert outcomes == ["invalid_code"]


def test_exit_zero_with_error_payload_is_not_recorded_as_success(executor):
    # The sandbox runner catches a failing metric and prints its own 400 payload
    # while still exiting 0; the HTTP layer aborts with that code.
    body = {"code": 400, "error": "boom"}

    response, outcomes = run_with_result(executor, 0, json.dumps(body).encode("utf-8"))

    assert response == body
    assert outcomes == ["invalid_code"]


def test_nonzero_exit_records_invalid_code(executor):
    response, outcomes = run_with_result(executor, 1, json.dumps({"error": "bad metric"}).encode("utf-8"))

    assert response == {"code": 400, "error": "bad metric"}
    assert outcomes == ["invalid_code"]
