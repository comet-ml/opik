"""The Docker sandbox encodes a metric's result as the process executor does.

The sandbox's scoring_runner.py is run as the container runs it, with code and data as arguments, and its output
goes through the executors' own parse_execution_result. The process executor's encoding is the reference: the same
metric through run_user_code, encoded as its worker encodes replies.
"""
import importlib.util
import json
import os
import subprocess
import sys
from pathlib import Path

import pytest

from opik_backend import process_worker
from opik_backend.executor import ExecutionResult
from opik_backend.executor_process import ProcessExecutor

RUNNER = Path(__file__).resolve().parents[3] / "opik-sandbox-executor-python" / "scoring_runner.py"

pytestmark = [
    pytest.mark.skipif(not RUNNER.exists(), reason="sandbox sources not present (image builds ship the python app alone)"),
    pytest.mark.skipif(importlib.util.find_spec("_opik") is None, reason="opik build without the _opik stub layer"),
]

METADATA_METRIC = """
import dataclasses
import datetime
import decimal
import uuid

from opik.evaluation.metrics import base_metric, score_result


@dataclasses.dataclass
class Point:
    x: int
    y: decimal.Decimal


class WithMetadata(base_metric.BaseMetric):
    def __init__(self):
        super().__init__(name="with_metadata", track=False)

    def score(self, **ignored_kwargs):
        when = datetime.datetime(2024, 1, 2, 3, 4, 5, 678000)
        return score_result.ScoreResult(name=self.name, value=1.0, metadata={
            "naive": when,
            "aware": when.replace(tzinfo=datetime.timezone(datetime.timedelta(hours=2))),
            "day": datetime.date(2024, 1, 2),
            "amount": decimal.Decimal("1.10"),
            "id": uuid.UUID("12345678-1234-5678-1234-567812345678"),
            "nested": {"list": [decimal.Decimal("2"), (uuid.UUID(int=1), when)], "deep": {"when": when}},
            "point": Point(1, decimal.Decimal("0.5")),
            "nan": float("nan"),
        })
"""

UNENCODABLE_METRIC = """
from opik.evaluation.metrics import base_metric, score_result


class Unencodable(base_metric.BaseMetric):
    def __init__(self):
        super().__init__(name="unencodable", track=False)

    def score(self, **ignored_kwargs):
        return score_result.ScoreResult(name=self.name, value=1.0,
                                        metadata={"fine": 1, "bad": {"inner": [object()]}})
"""


def _run_in_sandbox(code: str):
    """As the Docker executor runs the runner: code, data and payload type as arguments; output then parsed."""
    run = subprocess.run([sys.executable, str(RUNNER), code, json.dumps({}), ""], capture_output=True, timeout=120,
                         env={**os.environ, "PYTHON_COLORS": "0"})
    return run, ProcessExecutor().parse_execution_result(ExecutionResult(exit_code=run.returncode, output=run.stdout))


def test_metadata_values_are_encoded_as_the_process_executor_encodes_them():
    run, parsed = _run_in_sandbox(METADATA_METRIC)

    expected = json.dumps(process_worker.run_user_code(METADATA_METRIC, {}), default=process_worker._json_value)
    assert run.returncode == 0, run.stderr
    assert run.stdout.decode().strip().splitlines()[-1] == expected
    assert parsed["scores"][0]["metadata"]["naive"] == "Tue, 02 Jan 2024 03:04:05 GMT"
    assert parsed["scores"][0]["metadata"]["aware"] == "Tue, 02 Jan 2024 01:04:05 GMT"


def test_unencodable_metadata_names_the_key_and_type():
    run, parsed = _run_in_sandbox(UNENCODABLE_METRIC)

    assert run.returncode == 1
    assert parsed == {"code": 400, "error": "The metric's ScoreResult metadata key 'bad' holds a value of type "
                                            "'object', which can't be returned as JSON"}
