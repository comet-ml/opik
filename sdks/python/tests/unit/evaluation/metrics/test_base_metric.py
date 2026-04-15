import asyncio
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
