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
from opentelemetry.trace import StatusCode

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


def run_with_result(executor, exit_code, output: bytes, apply_rejection_policy=True):
    """Run scoring against a container returning a canned exec result."""
    container = MagicMock()
    container.exec_run.return_value = MagicMock(exit_code=exit_code, output=output)
    with patch.object(executor, "get_container", return_value=container):
        with patch.object(executor, "_record_execution_outcome") as record:
            response = executor.run_scoring(
                code="<unused>",
                data=DATA,
                apply_rejection_policy=apply_rejection_policy,
            )
    return response, [call.args[0] for call in record.call_args_list]


def recording_span(executor):
    """Capture the span the run should annotate, and return it after the call."""
    span = MagicMock()
    span_context = MagicMock()
    span_context.__enter__.return_value = span
    span_context.__exit__.return_value = False
    executor.tracer = MagicMock()
    executor.tracer.start_as_current_span.return_value = span_context
    return span


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


def test_exit_zero_with_no_scores_is_not_recorded_as_success(executor):
    # A metric whose score() returns nothing usable prints a well-formed
    # {"scores": []} and still exits 0. parse_execution_result hands the parsed object
    # back verbatim, so there is no "code" to key on and the counter used to read this as
    # a clean run — while the HTTP layer aborts the request with a 400.
    body = {"scores": []}

    response, outcomes = run_with_result(executor, 0, json.dumps(body).encode("utf-8"))

    assert response == body
    assert outcomes == ["invalid_code"]


@pytest.mark.parametrize(
    "scores",
    [
        pytest.param(None, id="null-scores"),
        pytest.param("not a list", id="string-scores"),
        pytest.param({"name": "m"}, id="object-scores"),
    ],
)
def test_exit_zero_with_non_list_scores_is_not_recorded_as_success(executor, scores):
    # A null or non-list `scores` used to reach `len()` inside the executor and turn
    # a 400 from the endpoint into a 500, while telemetry had already decided the
    # response was rejected. Both now use the same guard.
    body = {"scores": scores}

    response, outcomes = run_with_result(executor, 0, json.dumps(body).encode("utf-8"))

    assert response == body
    assert outcomes == ["invalid_code"]


@pytest.mark.parametrize(
    "score",
    [
        pytest.param({"name": "m"}, id="missing-value"),
        pytest.param({"name": "m", "value": None}, id="null-value"),
        pytest.param({"name": "m", "value": 0.0, "scoring_failed": True}, id="scoring-failed"),
    ],
)
def test_exit_zero_with_no_storable_score_is_not_recorded_as_success(executor, score):
    # Same gap one step further on: there is a score, but the backend cannot store it —
    # no value, or the metric itself flagged it as failed. execute_evaluator_python rejects
    # both with a 400, so neither may be counted as a success.
    body = {"scores": [score]}

    response, outcomes = run_with_result(executor, 0, json.dumps(body).encode("utf-8"))

    assert response == body
    assert outcomes == ["invalid_code"]


def test_exit_zero_with_one_storable_score_is_still_a_success(executor):
    # The gate is per response, not per score: a mixed list is passed through on purpose so
    # the usable scores still reach the backend, and that has to stay a success.
    body = {"scores": [{"name": "bad", "value": None}, {"name": "m", "value": 0.5}]}

    response, outcomes = run_with_result(executor, 0, json.dumps(body).encode("utf-8"))

    assert response == body
    assert outcomes == ["success"]


def test_a_genuine_zero_value_is_a_usable_score(executor):
    # has_usable_score keys on `value is not None`, so 0.0 is storable and must not be
    # confused with the missing value above.
    body = {"scores": [{"name": "m", "value": 0.0}]}

    response, outcomes = run_with_result(executor, 0, json.dumps(body).encode("utf-8"))

    assert response == body
    assert outcomes == ["success"]


def test_rejected_response_pins_the_reason_on_the_span(executor):
    # A rejected run used to say only "result_code=None"; the span now carries why the
    # response was rejected so "no usable score" is distinguishable from a real failure.
    span = recording_span(executor)

    body = {"scores": [{"name": "m"}]}
    response, outcomes = run_with_result(executor, 0, json.dumps(body).encode("utf-8"))

    assert response == body
    assert outcomes == ["invalid_code"]
    attributes = {call.args[0]: call.args[1] for call in span.set_attribute.call_args_list}
    status = span.set_status.call_args.args[0]
    assert status.status_code == StatusCode.ERROR
    assert attributes["error.reason"] == "no_usable_score"
    assert attributes["error.type"] == "invalid_code"
    assert attributes["error.exit_code"] == 0
    assert "error.result_code" not in attributes
    assert "reason=no_usable_score" in status.description


def test_non_scalar_result_code_is_summarized_on_the_span(executor):
    # `code` comes from the metric's own JSON; a dict or a long string must not be
    # copied into the span status or attributes verbatim.
    span = recording_span(executor)

    body = {"code": {"detail": "x" * 1000}, "scores": []}
    response, outcomes = run_with_result(executor, 0, json.dumps(body).encode("utf-8"))

    assert response == body
    assert outcomes == ["invalid_code"]
    attributes = {call.args[0]: call.args[1] for call in span.set_attribute.call_args_list}
    status = span.set_status.call_args.args[0]
    assert attributes["error.result_code"] == "dict"
    assert "result_code=dict" in status.description
    assert "detail" not in status.description


def test_string_result_code_keeps_its_value_within_a_bound(executor):
    # A status-like code such as "429" is useful on the span, unlike "str".
    span = recording_span(executor)
    body = {"code": "429", "scores": []}

    response, outcomes = run_with_result(executor, 0, json.dumps(body).encode("utf-8"))

    assert response == body
    assert outcomes == ["invalid_code"]
    attributes = {call.args[0]: call.args[1] for call in span.set_attribute.call_args_list}
    assert attributes["error.result_code"] == "429"

    long_span = recording_span(executor)
    long_body = {"code": "9" * 100, "scores": []}
    run_with_result(executor, 0, json.dumps(long_body).encode("utf-8"))
    long_attributes = {
        call.args[0]: call.args[1] for call in long_span.set_attribute.call_args_list
    }
    assert long_attributes["error.result_code"] == "9" * 64


def test_nonzero_exit_is_recorded_as_the_reason(executor):
    # The sandbox always hands a non-zero exit back as an error payload, so checking
    # that payload first would make every crash indistinguishable from a metric that
    # printed its own error object.
    span = recording_span(executor)

    response, outcomes = run_with_result(
        executor, 1, json.dumps({"error": "bad metric"}).encode("utf-8")
    )

    assert response == {"code": 400, "error": "bad metric"}
    assert outcomes == ["invalid_code"]
    attributes = {call.args[0]: call.args[1] for call in span.set_attribute.call_args_list}
    status = span.set_status.call_args.args[0]
    assert attributes["error.reason"] == "nonzero_exit"
    assert "reason=nonzero_exit" in status.description


def test_studio_caller_opts_out_of_the_rejection_policy(executor):
    # The optimization studio accepts an empty score list as a normal result, so its
    # responses must not be counted as rejections (nor have the span marked as an
    # error). The outcome is still recorded, so studio runs stay in the counter.
    span = recording_span(executor)
    body = {"scores": []}

    response, outcomes = run_with_result(
        executor, 0, json.dumps(body).encode("utf-8"), apply_rejection_policy=False
    )

    assert response == body
    assert outcomes == ["success"]
    span.set_status.assert_not_called()


@pytest.mark.parametrize(
    "body",
    [
        pytest.param({}, id="missing-scores"),
        pytest.param({"scores": None}, id="null-scores"),
        pytest.param({"scores": [{"name": "m"}]}, id="missing-value"),
        pytest.param(
            {"scores": [{"name": "m", "value": 0.0, "scoring_failed": True}]},
            id="scoring-failed",
        ),
    ],
)
def test_studio_caller_tolerates_a_missing_or_unusable_score_list(executor, body):
    # Studio turns these into a 0.0 result instead of failing the item, so they
    # stay out of the rejection counter and the span stays OK.
    span = recording_span(executor)

    response, outcomes = run_with_result(
        executor, 0, json.dumps(body).encode("utf-8"), apply_rejection_policy=False
    )

    assert response == body
    assert outcomes == ["success"]
    span.set_status.assert_not_called()


def test_studio_caller_still_counts_an_error_payload(executor):
    # A metric that catches its own exception prints {"error": ...} and exits 0.
    # Studio turns that into a 0.0 result, which used to make a broken metric look
    # like a real zero; the rejection must still be counted and pinned on the span.
    span = recording_span(executor)
    body = {"code": 400, "error": "boom"}

    response, outcomes = run_with_result(
        executor, 0, json.dumps(body).encode("utf-8"), apply_rejection_policy=False
    )

    assert response == body
    assert outcomes == ["invalid_code"]
    attributes = {call.args[0]: call.args[1] for call in span.set_attribute.call_args_list}
    status = span.set_status.call_args.args[0]
    assert status.status_code == StatusCode.ERROR
    assert attributes["error.reason"] == "error_payload"


def test_studio_caller_still_counts_non_list_scores(executor):
    # Studio indexes the list, so a string or object is not tolerated; only a
    # missing, null, empty or all-unusable list is.
    span = recording_span(executor)
    body = {"scores": "not a list"}

    response, outcomes = run_with_result(
        executor, 0, json.dumps(body).encode("utf-8"), apply_rejection_policy=False
    )

    assert response == body
    assert outcomes == ["invalid_code"]
    attributes = {call.args[0]: call.args[1] for call in span.set_attribute.call_args_list}
    assert attributes["error.reason"] == "no_scores"


def test_studio_caller_still_counts_a_crash(executor):
    # Opting out of the rejection policy must not hide a non-zero exit: crashes are
    # recorded exactly as they are for the evaluator endpoint.
    span = recording_span(executor)

    response, outcomes = run_with_result(
        executor,
        1,
        json.dumps({"error": "bad metric"}).encode("utf-8"),
        apply_rejection_policy=False,
    )

    assert response == {"code": 400, "error": "bad metric"}
    assert outcomes == ["invalid_code"]
    attributes = {call.args[0]: call.args[1] for call in span.set_attribute.call_args_list}
    assert attributes["error.reason"] == "nonzero_exit"


def test_studio_code_metric_forwards_the_rejection_policy_opt_out():
    # Pin the wiring: dropping the kwarg here would silently start counting studio
    # empty/all-failed scores as rejections without any other test failing.
    from opik_backend.studio import metrics as studio_metrics

    fake_executor = MagicMock()
    fake_executor.run_scoring.return_value = {"scores": []}

    with (
        patch.object(studio_metrics, "EXECUTION_STRATEGY", "docker"),
        patch.object(studio_metrics, "_code_metric_executor", fake_executor),
    ):
        result = studio_metrics._run_code_metric("<code>", {"output": "x"})

    assert result == {"scores": []}
    fake_executor.run_scoring.assert_called_once_with(
        "<code>", {"output": "x"}, apply_rejection_policy=False
    )
