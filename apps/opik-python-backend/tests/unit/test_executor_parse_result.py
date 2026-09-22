"""Tests for CodeExecutorBase.parse_execution_result"""
import json

import pytest

from opik_backend.executor import CodeExecutorBase, ExecutionResult


class _Executor(CodeExecutorBase):
    """parse_execution_result lives on the base class; run_scoring is abstract but unused here."""

    def run_scoring(self, code, data, payload_type=None):  # pragma: no cover - not exercised
        raise NotImplementedError


def parse(exit_code: int, output: bytes) -> dict:
    return _Executor().parse_execution_result(ExecutionResult(exit_code=exit_code, output=output))


def test_success_returns_last_line_payload():
    payload = {"scores": [{"name": "m", "value": 1.0}]}
    result = parse(0, b"some stdout noise\n" + json.dumps(payload).encode("utf-8"))

    assert result == payload


def test_success_with_no_output_reports_a_client_error():
    # Used to raise IndexError from splitlines()[-1], which run_scoring's catch-all reported as
    # "An unexpected error occurred" with HTTP 500 — retried by the caller and blamed on us.
    result = parse(0, b"")

    assert result == {"code": 400, "error": "Execution failed: the metric produced no output"}


def test_success_with_whitespace_only_output_reports_a_client_error():
    result = parse(0, b"   \n\n  ")

    assert result == {"code": 400, "error": "Execution failed: the metric produced no output"}


def test_success_with_non_json_last_line_reports_a_client_error():
    result = parse(0, b"not json at all")

    assert result == {"code": 400, "error": "Execution failed: the metric returned an unparseable result"}


@pytest.mark.parametrize(
    "last_line",
    ["null", "123", "true", '"done"', "[1, 2]"],
    ids=["null", "int", "bool", "str", "list"],
)
def test_success_with_non_object_json_reports_a_client_error(last_line):
    # Valid JSON that is not an object used to be handed straight to the HTTP layer, which then
    # raised TypeError ("error" in None) or AttributeError (str/list have no .get) and returned 500.
    result = parse(0, last_line.encode("utf-8"))

    assert result == {"code": 400, "error": "Execution failed: the metric did not return a JSON object"}


def test_failure_surfaces_the_user_error_as_400():
    result = parse(1, json.dumps({"error": "bad metric"}).encode("utf-8"))

    assert result == {"code": 400, "error": "bad metric"}


def test_failure_with_no_output_falls_back_to_the_invalid_metric_message():
    result = parse(1, b"")

    assert result == {"code": 400, "error": "Execution failed: Python code contains an invalid metric"}
