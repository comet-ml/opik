"""Re-scoring reports task-span metrics that cannot run without a task."""

import contextlib
import logging
from typing import Any, List, Optional
from unittest import mock

import opik
import pytest
from opik import evaluation, url_helpers
from opik.api_objects import opik_client
from opik.evaluation import rest_operations, test_case
from opik.evaluation.engine import engine
from opik.evaluation.metrics import base_metric, score_result
from opik.evaluation.types import ErrorTolerance
from opik.message_processing.emulation import models


class RequiresTaskSpan(base_metric.BaseMetric):
    def __init__(self, name: str) -> None:
        super().__init__(name=name, track=False)

    def score(self, task_span: Any, **ignored: Any) -> score_result.ScoreResult:
        return score_result.ScoreResult(name=self.name, value=1.0)


class AlwaysPasses(base_metric.BaseMetric):
    def __init__(self) -> None:
        super().__init__(name="always_passes", track=False)

    def score(self, output: str, **ignored: Any) -> score_result.ScoreResult:
        return score_result.ScoreResult(name=self.name, value=1.0)


def _test_cases() -> List[test_case.TestCase]:
    return [
        test_case.TestCase(
            trace_id="trace-0",
            dataset_item_id="item-0",
            task_output={"output": "hello"},
            dataset_item_content={"input": "hi", "reference": "hello"},
        )
    ]


def _rescoring_lookup_patches():
    mock_experiment = mock.Mock(id="exp-id", name="exp-name", dataset_name="ds-name")
    return [
        mock.patch.object(
            rest_operations,
            "get_experiment_with_unique_name",
            return_value=mock_experiment,
        ),
        mock.patch.object(
            opik_client.Opik, "get_dataset", return_value=mock.Mock(id="dataset-id")
        ),
        mock.patch.object(
            rest_operations, "get_experiment_test_cases", return_value=_test_cases()
        ),
        mock.patch.object(
            rest_operations, "get_trace_project_name", return_value="test-project"
        ),
        mock.patch.object(
            url_helpers,
            "get_experiment_url_by_id",
            return_value="http://example.com/exp",
        ),
    ]


def _score_test_cases(metrics: List[base_metric.BaseMetric]):
    client = opik.Opik(project_name="test-project")
    evaluation_engine = engine.EvaluationEngine(
        client=client,
        project_name="test-project",
        workers=1,
        verbose=0,
        source="experiment",
        error_tolerance=ErrorTolerance.METRIC_ERRORS,
    )
    return evaluation_engine.score_test_cases(
        test_cases=_test_cases(),
        scoring_metrics=metrics,
        scoring_key_mapping=None,
    )


def test_score_test_cases__skips_task_span_metrics_with_warning_and_scores_regular_metrics(
    fake_backend, caplog
):
    caplog.set_level(logging.WARNING, logger="opik.evaluation.engine.engine")
    with mock.patch.object(
        rest_operations,
        "log_test_result_feedback_scores",
        wraps=rest_operations.log_test_result_feedback_scores,
    ) as log_spy:
        results = _score_test_cases(
            [
                AlwaysPasses(),
                RequiresTaskSpan("first_span_metric"),
                RequiresTaskSpan("second_span_metric"),
            ]
        )

    assert [score.name for score in results[0].score_results] == ["always_passes"]
    assert results[0].score_results[0].value == 1.0
    assert results[0].score_results[0].scoring_failed is False
    warnings = [
        record.getMessage()
        for record in caplog.records
        if record.name == "opik.evaluation.engine.engine"
    ]
    assert len(warnings) == 2
    assert "first_span_metric" in warnings[0]
    assert "second_span_metric" in warnings[1]
    assert all("no task span" in message for message in warnings)
    assert all("evaluate()" in message for message in warnings)
    logged = [
        score.name
        for call in log_spy.call_args_list
        for score in call.kwargs["score_results"]
    ]
    assert logged == ["always_passes"]


@pytest.mark.parametrize("requires_span", [True, False])
def test_evaluate_experiment__task_span_scorer_is_skipped_with_warning(
    fake_backend, caplog, requires_span
):
    if requires_span:

        def task_span_scorer(task_span: models.SpanModel) -> score_result.ScoreResult:
            return score_result.ScoreResult(name="task_span_scorer", value=1.0)

    else:

        def task_span_scorer(
            task_span: Optional[models.SpanModel] = None,
        ) -> score_result.ScoreResult:
            return score_result.ScoreResult(
                name="task_span_scorer",
                value=1.0 if task_span is not None else 0.0,
            )

    caplog.set_level(logging.WARNING, logger="opik.evaluation.engine.engine")
    with contextlib.ExitStack() as stack:
        for patch in _rescoring_lookup_patches():
            stack.enter_context(patch)

        log_spy = stack.enter_context(
            mock.patch.object(
                rest_operations,
                "log_test_result_feedback_scores",
                wraps=rest_operations.log_test_result_feedback_scores,
            )
        )
        result = evaluation.evaluate_experiment(
            experiment_name="exp-name",
            scoring_metrics=[AlwaysPasses()],
            scoring_functions=[task_span_scorer],
            verbose=0,
        )

    assert [score.name for score in result.test_results[0].score_results] == [
        "always_passes"
    ]
    assert result.test_results[0].score_results[0].scoring_failed is False
    warnings = [
        record.getMessage()
        for record in caplog.records
        if record.name == "opik.evaluation.engine.engine"
    ]
    assert len(warnings) == 1
    assert "task_span_scorer" in warnings[0]
    logged = [
        score.name
        for call in log_spy.call_args_list
        for score in call.kwargs["score_results"]
    ]
    assert logged == ["always_passes"]
