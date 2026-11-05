import logging
from typing import Any, Dict, Optional
from unittest import mock

import pytest

from opik import evaluation, exceptions, url_helpers
from opik.api_objects import opik_client
from opik.evaluation import metrics, rest_operations, test_case
from opik.evaluation.engine import engine
from opik.evaluation.metrics import base_metric, score_result
from opik.message_processing.emulation import models


def _make_mock_experiment(
    id: str = "exp-id",
    name: str = "exp-name",
    dataset_name: str = "dataset-name",
) -> mock.Mock:
    exp = mock.Mock()
    exp.id = id
    exp.name = name
    exp.dataset_name = dataset_name
    return exp


def _make_mock_dataset(id: str = "dataset-id") -> mock.Mock:
    ds = mock.Mock()
    ds.id = id
    return ds


def _make_test_case(
    trace_id: str = "trace-id-1",
    dataset_item_id: str = "item-id-1",
    task_output: Dict[str, Any] = None,
    dataset_item_content: Dict[str, Any] = None,
) -> test_case.TestCase:
    return test_case.TestCase(
        trace_id=trace_id,
        dataset_item_id=dataset_item_id,
        task_output=task_output or {"output": "hello"},
        dataset_item_content=dataset_item_content
        or {"input": "hi", "reference": "hello"},
    )


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


def test_evaluate_experiment__no_test_cases__raises_empty_experiment(fake_backend):
    mock_experiment = _make_mock_experiment()
    mock_dataset = _make_mock_dataset()

    with mock.patch.object(
        rest_operations,
        "get_experiment_with_unique_name",
        return_value=mock_experiment,
    ):
        with mock.patch.object(
            opik_client.Opik, "get_dataset", return_value=mock_dataset
        ):
            with mock.patch.object(
                rest_operations, "get_experiment_test_cases", return_value=[]
            ):
                with pytest.raises(exceptions.EmptyExperiment):
                    evaluation.evaluate_experiment(
                        experiment_name="exp-name",
                        scoring_metrics=[metrics.Equals()],
                    )


def test_evaluate_experiment__happyflow(fake_backend):
    mock_experiment = _make_mock_experiment()
    mock_dataset = _make_mock_dataset()
    test_cases = [
        _make_test_case(
            trace_id="trace-1",
            task_output={"output": "hello"},
            dataset_item_content={"input": "hi", "reference": "hello"},
        ),
        _make_test_case(
            trace_id="trace-2",
            task_output={"output": "bye"},
            dataset_item_content={"input": "ciao", "reference": "bye"},
        ),
    ]
    mock_score_results = [
        score_result.ScoreResult(name="equals_metric", value=1.0),
        score_result.ScoreResult(name="equals_metric", value=1.0),
    ]
    mock_test_results = [
        mock.Mock(score_results=mock_score_results) for _ in test_cases
    ]

    with mock.patch.object(
        rest_operations,
        "get_experiment_with_unique_name",
        return_value=mock_experiment,
    ):
        with mock.patch.object(
            opik_client.Opik, "get_dataset", return_value=mock_dataset
        ):
            with mock.patch.object(
                rest_operations, "get_experiment_test_cases", return_value=test_cases
            ):
                with mock.patch.object(
                    rest_operations,
                    "get_trace_project_name",
                    return_value="test-project",
                ):
                    with mock.patch.object(
                        url_helpers,
                        "get_experiment_url_by_id",
                        return_value="http://example.com/exp",
                    ):
                        with mock.patch.object(
                            engine.EvaluationEngine,
                            "score_test_cases",
                            return_value=mock_test_results,
                        ):
                            result = evaluation.evaluate_experiment(
                                experiment_name="exp-name",
                                scoring_metrics=[metrics.Equals()],
                                verbose=0,
                            )

    assert result.experiment_id == "exp-id"
    assert result.experiment_name == "exp-name"
    assert result.dataset_id == "dataset-id"
    assert result.test_results == mock_test_results


def test_evaluate_experiment__with_experiment_id__uses_get_by_id(fake_backend):
    mock_experiment = _make_mock_experiment(id="explicit-exp-id")
    mock_dataset = _make_mock_dataset()
    test_cases = [_make_test_case()]

    mock_get_by_id = mock.Mock(return_value=mock_experiment)
    mock_get_by_name = mock.Mock()

    with mock.patch.object(opik_client.Opik, "get_experiment_by_id", mock_get_by_id):
        with mock.patch.object(
            rest_operations, "get_experiment_with_unique_name", mock_get_by_name
        ):
            with mock.patch.object(
                opik_client.Opik, "get_dataset", return_value=mock_dataset
            ):
                with mock.patch.object(
                    rest_operations,
                    "get_experiment_test_cases",
                    return_value=test_cases,
                ):
                    with mock.patch.object(
                        rest_operations,
                        "get_trace_project_name",
                        return_value="test-project",
                    ):
                        with mock.patch.object(
                            url_helpers,
                            "get_experiment_url_by_id",
                            return_value="http://example.com/exp",
                        ):
                            with mock.patch.object(
                                engine.EvaluationEngine,
                                "score_test_cases",
                                return_value=[],
                            ):
                                evaluation.evaluate_experiment(
                                    experiment_name="ignored-name",
                                    experiment_id="explicit-exp-id",
                                    scoring_metrics=[],
                                    verbose=0,
                                )

    mock_get_by_id.assert_called_once_with(id="explicit-exp-id")
    mock_get_by_name.assert_not_called()


def test_evaluate_experiment__skips_task_span_metrics_with_warning_and_scores_regular_metrics(
    fake_backend, caplog
):
    caplog.set_level(logging.WARNING, logger="opik.evaluation.engine.engine")
    with (
        mock.patch.object(
            rest_operations,
            "get_experiment_with_unique_name",
            return_value=_make_mock_experiment(),
        ),
        mock.patch.object(
            opik_client.Opik, "get_dataset", return_value=_make_mock_dataset()
        ),
        mock.patch.object(
            rest_operations,
            "get_experiment_test_cases",
            return_value=[_make_test_case()],
        ),
        mock.patch.object(
            rest_operations, "get_trace_project_name", return_value="test-project"
        ),
        mock.patch.object(
            url_helpers,
            "get_experiment_url_by_id",
            return_value="http://example.com/exp",
        ),
        mock.patch.object(
            rest_operations,
            "log_test_result_feedback_scores",
            wraps=rest_operations.log_test_result_feedback_scores,
        ) as log_spy,
    ):
        result = evaluation.evaluate_experiment(
            experiment_name="exp-name",
            scoring_metrics=[
                AlwaysPasses(),
                RequiresTaskSpan("first_span_metric"),
                RequiresTaskSpan("second_span_metric"),
            ],
            scoring_threads=1,
            verbose=0,
        )

    scores = result.test_results[0].score_results
    assert [score.name for score in scores] == ["always_passes"]
    assert scores[0].value == 1.0
    assert scores[0].scoring_failed is False
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
    with (
        mock.patch.object(
            rest_operations,
            "get_experiment_with_unique_name",
            return_value=_make_mock_experiment(),
        ),
        mock.patch.object(
            opik_client.Opik, "get_dataset", return_value=_make_mock_dataset()
        ),
        mock.patch.object(
            rest_operations,
            "get_experiment_test_cases",
            return_value=[_make_test_case()],
        ),
        mock.patch.object(
            rest_operations, "get_trace_project_name", return_value="test-project"
        ),
        mock.patch.object(
            url_helpers,
            "get_experiment_url_by_id",
            return_value="http://example.com/exp",
        ),
        mock.patch.object(
            rest_operations,
            "log_test_result_feedback_scores",
            wraps=rest_operations.log_test_result_feedback_scores,
        ) as log_spy,
    ):
        result = evaluation.evaluate_experiment(
            experiment_name="exp-name",
            scoring_metrics=[AlwaysPasses()],
            scoring_functions=[task_span_scorer],
            scoring_threads=1,
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
