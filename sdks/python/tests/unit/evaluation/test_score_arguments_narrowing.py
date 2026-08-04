"""Scoring inputs are narrowed to what each metric's signature accepts.

A metric without ``**kwargs`` used to fail on every item with
``score() got an unexpected keyword argument 'input'`` (OPIK-6925).
"""

from typing import Any, List, Optional
from unittest import mock

import pytest

from opik import exceptions, url_helpers
from opik.api_objects import opik_client
from opik.api_objects.dataset import dataset_item
from opik import evaluation
from opik.evaluation.metrics import base_metric, score_result


class NarrowSignature(base_metric.BaseMetric):
    """Declares exactly what it needs and nothing else — no **kwargs."""

    def __init__(self, track: bool = False) -> None:
        super().__init__(name="narrow_metric", track=track)

    def score(self, output: str, expected_label: str) -> score_result.ScoreResult:
        return score_result.ScoreResult(
            name=self.name, value=1.0 if output == expected_label else 0.0
        )


class AbsorbsKwargs(base_metric.BaseMetric):
    def __init__(self) -> None:
        super().__init__(name="wide_metric", track=False)

    def score(self, output: str, **ignored: Any) -> score_result.ScoreResult:
        return score_result.ScoreResult(
            name=self.name, value=1.0, reason=f"saw {sorted(ignored)}"
        )


def _run_evaluation(
    metrics: List[base_metric.BaseMetric],
    items: Optional[List[dataset_item.DatasetItem]] = None,
):
    items = items or [
        dataset_item.DatasetItem(
            id="dataset-item-id-1", input="q", output="a", expected_label="a"
        )
    ]

    mock_dataset = mock.MagicMock(
        spec=[
            "__internal_api__stream_items_as_dataclasses__",
            "id",
            "dataset_items_count",
            "get_version_info",
            "get_execution_policy",
            "project_name",
            "get_evaluators",
        ]
    )
    mock_dataset.name = "the-dataset-name"
    mock_dataset.id = "the-dataset-id"
    mock_dataset.dataset_items_count = len(items)
    mock_dataset.get_version_info.return_value = None
    mock_dataset.project_name = None
    mock_dataset.get_execution_policy.return_value = {}
    mock_dataset.get_evaluators.return_value = []
    mock_dataset.__internal_api__stream_items_as_dataclasses__.return_value = iter(
        items
    )

    mock_experiment = mock.Mock()
    mock_experiment.prompts = None

    with mock.patch.object(
        opik_client.Opik, "create_experiment", mock.Mock(return_value=mock_experiment)
    ):
        with mock.patch.object(
            url_helpers, "get_experiment_url_by_id", mock.Mock(return_value="any_url")
        ):
            return evaluation.evaluate(
                dataset=mock_dataset,
                task=lambda item: {"output": item["output"]},
                scoring_metrics=metrics,
                experiment_name="the-experiment-name",
                task_threads=1,
            )


@pytest.mark.parametrize("track", [False, True])
def test_evaluate__metric_without_kwargs__unrelated_dataset_keys_are_not_passed(
    fake_backend, track
):
    # `track=True` is the default and wraps score() — the signature must still
    # be introspectable through that wrapper.
    result = _run_evaluation([NarrowSignature(track=track)])

    score = result.test_results[0].score_results[0]
    assert score.name == "narrow_metric"
    assert score.scoring_failed is False
    assert score.value == 1.0


def test_evaluate__metric_with_kwargs__still_receives_everything(fake_backend):
    result = _run_evaluation([AbsorbsKwargs()])

    score = result.test_results[0].score_results[0]
    assert score.scoring_failed is False
    # Unchanged behaviour: a metric that opts into **kwargs sees all the keys.
    assert score.reason == "saw ['expected_label', 'id', 'input']"


def test_evaluate__metric_without_kwargs__missing_argument_still_raises(fake_backend):
    # Narrowing must not mask a key the metric actually declared: the dataset
    # below has no `expected_label`, and that has to stay a hard failure.
    items = [dataset_item.DatasetItem(id="dataset-item-id-1", input="q", output="a")]

    with pytest.raises(exceptions.ScoreMethodMissingArguments):
        _run_evaluation([NarrowSignature()], items=items)
