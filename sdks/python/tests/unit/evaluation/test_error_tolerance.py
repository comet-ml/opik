"""`error_tolerance` decides which failures abort an evaluation (OPIK-6925)."""

from typing import Any, List, Optional
from unittest import mock

import pytest

from opik import exceptions, url_helpers
from opik.api_objects import opik_client
from opik.api_objects.dataset import dataset_item
from opik import evaluation
from opik.evaluation.metrics import base_metric, score_result
from opik.evaluation.types import ErrorTolerance


class AlwaysPasses(base_metric.BaseMetric):
    def __init__(self) -> None:
        super().__init__(name="always_passes", track=False)

    def score(self, output: str, **ignored: Any) -> score_result.ScoreResult:
        return score_result.ScoreResult(name=self.name, value=1.0)


class RaisesInScore(base_metric.BaseMetric):
    def __init__(self) -> None:
        super().__init__(name="raises_in_score", track=False)

    def score(self, output: str, **ignored: Any) -> score_result.ScoreResult:
        raise ValueError("judge is unreachable")


class NeedsMissingArgument(base_metric.BaseMetric):
    """`expected_label` is in neither the dataset nor the task output, so argument
    validation fails before score() is entered."""

    def __init__(self) -> None:
        super().__init__(name="needs_missing_arg", track=False)

    def score(
        self, output: str, expected_label: str, **ignored: Any
    ) -> score_result.ScoreResult:
        return score_result.ScoreResult(name=self.name, value=1.0)


ITEM_WITH_BROKEN_EVALUATOR = dataset_item.DatasetItem(
    id="dataset-item-id-0",
    input="q",
    output="a",
    evaluators=[
        dataset_item.EvaluatorItem(
            name="broken_judge", type="llm_judge", config={"nonsense": True}
        )
    ],
)


def _run_evaluation(
    metrics: List[base_metric.BaseMetric],
    error_tolerance: ErrorTolerance = ErrorTolerance.METRIC_ERRORS,
    items: Optional[List[dataset_item.DatasetItem]] = None,
):
    items = items or [
        dataset_item.DatasetItem(id=f"dataset-item-id-{index}", input="q", output="a")
        for index in range(2)
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
                error_tolerance=error_tolerance,
            )


def _score_by_name(result, name: str) -> score_result.ScoreResult:
    return next(
        score for score in result.test_results[0].score_results if score.name == name
    )


def _assert_failure_is_reported(score: score_result.ScoreResult, exception_type: str):
    assert score.scoring_failed is True
    assert score.value == 0.0
    assert score.reason
    error_info = score.metadata["error_info"]
    assert error_info["exception_type"] == exception_type
    assert error_info["message"]
    assert error_info["traceback"]


# --- errors raised inside score() ------------------------------------------


@pytest.mark.parametrize(
    "error_tolerance",
    [ErrorTolerance.METRIC_ERRORS, ErrorTolerance.ALL_SCORING_ERRORS],
)
def test_evaluate__error_inside_score__tolerated_at_every_level(
    fake_backend, error_tolerance
):
    result = _run_evaluation(
        [AlwaysPasses(), RaisesInScore()], error_tolerance=error_tolerance
    )

    _assert_failure_is_reported(_score_by_name(result, "raises_in_score"), "ValueError")
    assert _score_by_name(result, "always_passes").scoring_failed is False


# --- errors raised before score() is entered -------------------------------


def test_evaluate__missing_score_argument__default_tolerance__evaluation_is_aborted(
    fake_backend,
):
    # METRIC_ERRORS is the default, so this is the long-standing behaviour.
    with pytest.raises(exceptions.ScoreMethodMissingArguments):
        _run_evaluation([AlwaysPasses(), NeedsMissingArgument()])


def test_evaluate__missing_score_argument__tolerance_all__accumulated_as_failed_score(
    fake_backend,
):
    result = _run_evaluation(
        [AlwaysPasses(), NeedsMissingArgument()],
        error_tolerance=ErrorTolerance.ALL_SCORING_ERRORS,
    )

    assert len(result.test_results) == 2
    score = _score_by_name(result, "needs_missing_arg")
    _assert_failure_is_reported(score, "ScoreMethodMissingArguments")
    assert "expected_label" in score.reason

    # Everything else is scored as usual and the failure is kept out of the stats.
    assert _score_by_name(result, "always_passes").scoring_failed is False
    aggregated = result.aggregate_evaluation_scores().aggregated_scores
    assert aggregated["always_passes"].mean == 1.0
    assert "needs_missing_arg" not in aggregated


def test_evaluate__item_evaluator_cannot_be_built__default_tolerance__evaluation_is_aborted(
    fake_backend,
):
    with pytest.raises(Exception):
        _run_evaluation([AlwaysPasses()], items=[ITEM_WITH_BROKEN_EVALUATOR])


def test_evaluate__item_evaluator_cannot_be_built__tolerance_all__accumulated_as_failed_score(
    fake_backend,
):
    result = _run_evaluation(
        [AlwaysPasses()],
        error_tolerance=ErrorTolerance.ALL_SCORING_ERRORS,
        items=[ITEM_WITH_BROKEN_EVALUATOR],
    )

    _assert_failure_is_reported(
        _score_by_name(result, "broken_judge"), "ValidationError"
    )
    assert _score_by_name(result, "always_passes").scoring_failed is False


# --- persistence and API surface ------------------------------------------


def test_evaluate__tolerated_failures__are_not_sent_to_the_backend(fake_backend):
    _run_evaluation(
        [AlwaysPasses(), NeedsMissingArgument()],
        error_tolerance=ErrorTolerance.ALL_SCORING_ERRORS,
    )

    logged_score_names = {
        score.name
        for trace in fake_backend.trace_trees
        for score in trace.feedback_scores or []
    }
    assert logged_score_names == {"always_passes"}


def test_evaluate__error_tolerance_accepts_plain_ints(fake_backend):
    result = _run_evaluation(
        [AlwaysPasses(), NeedsMissingArgument()], error_tolerance=20
    )

    assert _score_by_name(result, "needs_missing_arg").scoring_failed is True


def test_evaluate__error_tolerance_rejects_unknown_values(fake_backend):
    with pytest.raises(ValueError, match="not a valid ErrorTolerance"):
        _run_evaluation([AlwaysPasses()], error_tolerance=15)


def _spans_named(fake_backend, name: str):
    def walk(spans):
        for span in spans:
            yield span
            yield from walk(span.spans)

    return [
        span
        for trace in fake_backend.trace_trees
        for span in walk(trace.spans)
        if span.name == name
    ]


class TrackedNeedsMissingArgument(base_metric.BaseMetric):
    """Same as NeedsMissingArgument but traced, which is the default."""

    def __init__(self) -> None:
        super().__init__(name="tracked_needs_missing_arg")

    def score(
        self, output: str, expected_label: str, **ignored: Any
    ) -> score_result.ScoreResult:
        return score_result.ScoreResult(name=self.name, value=1.0)


def test_evaluate__tolerated_failure__is_reported_on_the_argument_span(fake_backend):
    # Without this the failure is invisible in the backend: no score is persisted,
    # and a metric that never runs produces no score span of its own.
    _run_evaluation(
        [AlwaysPasses(), TrackedNeedsMissingArgument()],
        error_tolerance=ErrorTolerance.ALL_SCORING_ERRORS,
    )

    spans = _spans_named(fake_backend, "tracked_needs_missing_arg.score_arguments")
    assert len(spans) == 2  # one per dataset item
    assert spans[0].error_info["exception_type"] == "ScoreMethodMissingArguments"
    assert "expected_label" in spans[0].error_info["message"]

    # The metric itself was never entered, so it has no span of its own.
    assert _spans_named(fake_backend, "tracked_needs_missing_arg") == []


def test_evaluate__argument_span__is_created_for_successful_scores_too(fake_backend):
    # The argument span is the engine's own step, not the metric's, so it exists
    # regardless of the outcome and regardless of the metric's `track` setting.
    _run_evaluation([AlwaysPasses()])

    spans = _spans_named(fake_backend, "always_passes.score_arguments")
    assert len(spans) == 2
    assert all(span.error_info is None for span in spans)
