"""OPIK-6925: an evaluation the user asked for must never vanish without a trace.

Failures that already abort ``evaluate()`` keep aborting — see
``test_evaluate___output_key_is_missing_in_task_output_dict...`` in
test_evaluate.py. These tests cover the cases that were silently dropped
instead.
"""

from typing import Any, List, Optional
from unittest import mock

from opik import url_helpers
from opik.api_objects import opik_client
from opik.api_objects.dataset import dataset_item
from opik import evaluation
from opik.evaluation.metrics import arguments_helpers, base_metric, score_result


class AlwaysPasses(base_metric.BaseMetric):
    def __init__(self) -> None:
        super().__init__(name="always_passes", track=False)

    def score(self, output: str, **ignored: Any) -> score_result.ScoreResult:
        return score_result.ScoreResult(name=self.name, value=1.0)


class HasDefaultForMappedArgument(base_metric.BaseMetric):
    """Scores whatever it is given, so a mapping that matches nothing does not
    raise — it just quietly scores the wrong thing."""

    def __init__(self) -> None:
        super().__init__(name="lenient_metric", track=False)

    def score(
        self, output: str, reference: str = "", **ignored: Any
    ) -> score_result.ScoreResult:
        return score_result.ScoreResult(
            name=self.name, value=1.0 if output == reference else 0.0
        )


def _run_evaluation(
    metrics: List[base_metric.BaseMetric],
    items: Optional[List[dataset_item.DatasetItem]] = None,
    scoring_key_mapping: Optional[dict] = None,
):
    items = items or [
        dataset_item.DatasetItem(id="dataset-item-id-1", input="q", output="a")
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
                scoring_key_mapping=scoring_key_mapping,
                experiment_name="the-experiment-name",
                task_threads=1,
            )


def test_evaluate__item_evaluator_type_is_unsupported__reported_as_a_failed_score(
    fake_backend,
):
    items = [
        dataset_item.DatasetItem(
            id="dataset-item-id-1",
            input="q",
            output="a",
            evaluators=[
                dataset_item.EvaluatorItem(
                    name="code_metric_evaluator",
                    type="code_metric",
                    config={},
                )
            ],
        )
    ]

    result = _run_evaluation([AlwaysPasses()], items=items)

    scores = {score.name: score for score in result.test_results[0].score_results}
    assert scores["code_metric_evaluator"].scoring_failed is True
    assert "Unsupported evaluator type" in scores["code_metric_evaluator"].reason

    # The evaluation itself is unaffected — an unknown type must not abort it.
    assert scores["always_passes"].scoring_failed is False

    # A failed score is not persisted, so it stays out of the experiment scores.
    logged_score_names = [
        score.name
        for trace in fake_backend.trace_trees
        for score in trace.feedback_scores or []
    ]
    assert logged_score_names == ["always_passes"]


def test_evaluate__scoring_key_mapping_matches_nothing__warning_is_logged(fake_backend):
    with mock.patch.object(arguments_helpers.LOGGER, "warning") as mock_warning:
        result = _run_evaluation(
            [HasDefaultForMappedArgument()],
            scoring_key_mapping={"reference": "no_such_dataset_key"},
        )

    # The metric still scores (it has a default), which is exactly why the
    # unmatched mapping has to be reported.
    assert result.test_results[0].score_results[0].scoring_failed is False

    logged = " ".join(str(call) for call in mock_warning.call_args_list)
    assert "no_such_dataset_key" in logged
    assert "not found in dataset item" in logged
