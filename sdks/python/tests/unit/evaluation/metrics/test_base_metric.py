import asyncio
import subprocess
import sys

import pytest
from typing import Any, List, Union

from opik.evaluation.metrics import base_metric, score_result


class DummyMetric(base_metric.BaseMetric):
    def score(
        self, *args: Any, **kwargs: Any
    ) -> Union[score_result.ScoreResult, List[score_result.ScoreResult]]:
        return score_result.ScoreResult(
            value=0.5, name=self.name, reason="Test metric score"
        )


class MyCustomMetric(base_metric.BaseMetric):
    """Same as the example in the docstring of BaseMetric."""

    def __init__(self, name: str, track: bool = True):
        super().__init__(name=name, track=track)

    def score(self, input: str, output: str, **ignored_kwargs: Any):
        # Add your logic here
        return score_result.ScoreResult(
            value=0, name=self.name, reason="Optional reason for the score"
        )


def test_base_metric_score_default_name():
    metric = DummyMetric()

    assert metric.name == "DummyMetric"
    assert metric.track is True

    actual_result = metric.score()

    expected_result = score_result.ScoreResult(
        name="DummyMetric", value=0.5, reason="Test metric score"
    )
    assert actual_result == expected_result


def test_base_metric_custom_name():
    metric = DummyMetric(name="custom_name", project_name="test_project")

    assert metric.name == "custom_name"
    assert metric.track is True

    actual_result = metric.score()

    expected_result = score_result.ScoreResult(
        name="custom_name", value=0.5, reason="Test metric score"
    )
    assert actual_result == expected_result


def test_my_custom_metric_example():
    metric = MyCustomMetric("some_name", track=False)

    assert metric.name == "some_name"
    assert metric.track is False

    actual_result = metric.score("some_input_data", "some_output_data")

    expected_result = score_result.ScoreResult(
        name="some_name", value=0, reason="Optional reason for the score"
    )
    assert actual_result == expected_result


def test_base_metric_project_name_with_track_false_raises_error():
    with pytest.raises(
        ValueError, match="project_name can be set only when `track` is set to True"
    ):
        DummyMetric(track=False, project_name="test_project")


def test_base_metric_ascore_returns_expected_result():
    metric = DummyMetric()
    actual_result = asyncio.run(metric.ascore())

    expected_result = score_result.ScoreResult(
        name="DummyMetric", value=0.5, reason="Test metric score"
    )
    assert actual_result == expected_result


def test_lightweight_mode_raises_clear_error_for_heavy_symbols():
    """Verify that accessing heavy symbols in lightweight mode gives a clear RuntimeError."""
    code = """
import sys, os
os.environ["OPIK_SCORING_LIGHTWEIGHT"] = "true"

import opik

for attr in ["track", "Opik", "evaluate", "configure"]:
    try:
        getattr(opik, attr)
        raise AssertionError(f"Expected RuntimeError for opik.{attr}")
    except RuntimeError as e:
        assert "lightweight mode" in str(e), f"Bad message for {attr}: {e}"
    except AttributeError:
        raise AssertionError(f"Got AttributeError instead of RuntimeError for opik.{attr}")

print("CLEAR_ERROR_OK")
"""
    result = subprocess.run(
        [sys.executable, "-c", code],
        capture_output=True,
        text=True,
    )
    assert result.returncode == 0, f"Subprocess failed:\n{result.stderr}"
    assert "CLEAR_ERROR_OK" in result.stdout


def test_lightweight_mode_only_loads_allowed_modules():
    """Guard against heavy imports leaking into lightweight mode.

    When OPIK_SCORING_LIGHTWEIGHT=true, only a small set of opik.* modules
    should be loaded — enough for BaseMetric and ScoreResult to work.
    Everything else (REST client, tracing, sentry, pydantic_settings, 40+
    metric classes, etc.) must stay unloaded so the sandbox containers start
    in ~40ms instead of ~2.5s.

    HOW TO FIX A FAILURE:
        If this test fails, it means a new opik.* module got imported at
        the top level of one of these files without being inside the
        `if not _LIGHTWEIGHT_MODE:` guard:
          - sdks/python/src/opik/__init__.py
          - sdks/python/src/opik/evaluation/__init__.py
          - sdks/python/src/opik/evaluation/metrics/__init__.py
          - sdks/python/src/opik/evaluation/metrics/base_metric.py
        Move the import inside the guard, or if it truly is needed in
        lightweight mode, add it to ALLOWED_MODULES below.

    Runs in a subprocess so the env var takes effect before any module is
    cached by the parent test process.
    """
    code = """
import sys, os
os.environ["OPIK_SCORING_LIGHTWEIGHT"] = "true"

from opik.evaluation.metrics import BaseMetric
from opik.evaluation.metrics.score_result import ScoreResult

# Verify basic functionality works
class SimpleMetric(BaseMetric):
    def score(self, **kwargs):
        return ScoreResult(name="simple", value=1.0)

metric = SimpleMetric()
result = metric.score()
assert result.name == "simple"
assert result.value == 1.0

# --- Allowlist check ---
# Only these opik.* modules should be loaded in lightweight mode.
# If you need to add a new module here, make sure it is truly lightweight
# (no transitive imports of pydantic_settings, httpx, sentry, etc.).
ALLOWED_MODULES = {
    "opik",
    "opik.evaluation",
    "opik.evaluation.metrics",
    "opik.evaluation.metrics.base_metric",
    "opik.evaluation.metrics.score_result",
}

loaded_opik = {m for m in sys.modules if m.startswith("opik")}
unexpected = sorted(loaded_opik - ALLOWED_MODULES)

if unexpected:
    print("FAIL")
    print(
        "Lightweight mode loaded unexpected opik modules.\\n"
        "These modules must be inside the `if not _LIGHTWEIGHT_MODE:` guard,\\n"
        "or added to ALLOWED_MODULES if they are genuinely lightweight.\\n"
        "Unexpected modules:\\n  " + "\\n  ".join(unexpected)
    )
    sys.exit(1)

print("LIGHTWEIGHT_OK")
"""
    result = subprocess.run(
        [sys.executable, "-c", code],
        capture_output=True,
        text=True,
    )
    assert result.returncode == 0, (
        f"Lightweight mode loaded unexpected modules.\n"
        f"See stdout for details:\n{result.stdout}\n{result.stderr}"
    )
    assert "LIGHTWEIGHT_OK" in result.stdout
