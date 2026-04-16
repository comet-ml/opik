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


class TestLightweightOpikPackage:
    """Tests for the _opik lightweight package and sys.modules patching.

    These run in subprocesses to get a clean module state.
    """

    def test_opik_lightweight_import_does_not_load_heavy_modules(self):
        """Verify that importing from _opik stays lightweight.

        The _opik package must only use stdlib modules. If this test fails,
        someone added a dependency to _opik that pulls in heavy packages.

        HOW TO FIX: Remove the heavy import from _opik/. The _opik package
        must only depend on stdlib (abc, dataclasses, typing).
        """
        code = """
import sys

from _opik import BaseMetric, ScoreResult

# Verify basic functionality works
class SimpleMetric(BaseMetric):
    def score(self, **kwargs):
        return ScoreResult(name="simple", value=1.0)

metric = SimpleMetric()
result = metric.score()
assert result.name == "simple"
assert result.value == 1.0

# Only these opik-related modules should be loaded
ALLOWED = {"_opik", "_opik._base_metric", "_opik._score_result"}
loaded = {m for m in sys.modules if m.startswith(("opik", "_opik"))}
unexpected = sorted(loaded - ALLOWED)

if unexpected:
    print("FAIL")
    print(
        "Lightweight _opik import loaded unexpected modules.\\n"
        "The _opik package must only use stdlib.\\n"
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
            f"Lightweight _opik loaded unexpected modules.\n"
            f"See stdout for details:\n{result.stdout}\n{result.stderr}"
        )
        assert "LIGHTWEIGHT_OK" in result.stdout

    def test_sys_modules_patch_intercepts_opik_imports(self):
        """Verify the sys.modules patching works like scoring_runner.py does.

        User code does `from opik.evaluation.metrics import BaseMetric` and
        it should resolve to the lightweight _opik.BaseMetric without
        triggering the real opik import.

        HOW TO FIX if this fails: Check that _opik._base_metric.BaseMetric
        and _opik._score_result.ScoreResult match the interface expected by
        opik.evaluation.metrics.BaseMetric users.
        """
        code = """
import sys
import types

import _opik._base_metric
import _opik._score_result

# Patch sys.modules the same way scoring_runner.py does
for name in ["opik", "opik.evaluation", "opik.evaluation.metrics"]:
    stub = types.ModuleType(name)
    stub.__path__ = []
    sys.modules[name] = stub

sys.modules["opik.evaluation.metrics.base_metric"] = _opik._base_metric
sys.modules["opik.evaluation.metrics.score_result"] = _opik._score_result
sys.modules["opik.evaluation.metrics"].base_metric = _opik._base_metric
sys.modules["opik.evaluation.metrics"].score_result = _opik._score_result
sys.modules["opik.evaluation.metrics"].BaseMetric = _opik._base_metric.BaseMetric
sys.modules["opik.evaluation.metrics"].ScoreResult = _opik._score_result.ScoreResult

# Now simulate what user code does
from opik.evaluation.metrics import BaseMetric
from opik.evaluation.metrics.score_result import ScoreResult

class UserMetric(BaseMetric):
    def score(self, output="", **kwargs):
        return ScoreResult(name="user_metric", value=0.75, reason="test")

metric = UserMetric()
result = metric.score(output="hello")
assert result.name == "user_metric"
assert result.value == 0.75
assert result.reason == "test"

# Verify heavy opik modules were NOT loaded
heavy = [m for m in sys.modules if m.startswith("opik.") and m not in {
    "opik.evaluation", "opik.evaluation.metrics",
    "opik.evaluation.metrics.base_metric", "opik.evaluation.metrics.score_result",
}]
if heavy:
    print("FAIL")
    print(f"Patching did not prevent heavy imports: {heavy}")
    sys.exit(1)

print("PATCH_OK")
"""
        result = subprocess.run(
            [sys.executable, "-c", code],
            capture_output=True,
            text=True,
        )
        assert result.returncode == 0, (
            f"sys.modules patching failed.\n"
            f"See stdout for details:\n{result.stdout}\n{result.stderr}"
        )
        assert "PATCH_OK" in result.stdout

    def test_opik_base_metric_is_subclass_of_lightweight(self):
        """Verify that opik.evaluation.metrics.BaseMetric subclasses _opik.BaseMetric.

        This ensures isinstance() works across both import paths.
        """
        from _opik import BaseMetric as LightweightBaseMetric
        from opik.evaluation.metrics import BaseMetric as FullBaseMetric

        assert issubclass(FullBaseMetric, LightweightBaseMetric)

    def test_score_result_is_same_class(self):
        """Verify that ScoreResult is the same class from both paths."""
        from _opik import ScoreResult as LightweightScoreResult
        from opik.evaluation.metrics.score_result import (
            ScoreResult as FullScoreResult,
        )

        assert LightweightScoreResult is FullScoreResult
