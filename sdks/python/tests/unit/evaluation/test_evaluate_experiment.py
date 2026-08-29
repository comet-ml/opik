from typing import Any, Dict
from unittest import mock

import pytest

from opik import evaluation, exceptions, url_helpers
from opik.api_objects import opik_client
from opik.evaluation import (
    evaluator as evaluator_module,
    metrics,
    rest_operations,
    test_case,
)
from opik.evaluation.engine import engine
from opik.evaluation.metrics import score_result


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


def test_evaluate_experiment__returns_preserved_scores(fake_backend):
    mock_experiment = _make_mock_experiment()
    mock_dataset = _make_mock_dataset()
    test_cases = [_make_test_case()]
    mock_test_results = [mock.Mock(score_results=[])]
    computed_score = score_result.ScoreResult(name="accuracy", value=0.9)
    effective_scores = [
        score_result.ScoreResult(name="existing", value=0.4),
        computed_score,
    ]
    mock_experiment.log_experiment_scores.return_value = effective_scores

    def compute_score(_):
        return computed_score

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
                                scoring_metrics=[],
                                experiment_scoring_functions=[compute_score],
                                verbose=0,
                            )

    assert result.experiment_scores == effective_scores
    mock_experiment.log_experiment_scores.assert_called_once_with(
        score_results=[computed_score],
        preserve_unrelated=True,
    )


def test_evaluate_experiment__deduplicates_successful_scores(fake_backend):
    mock_experiment = _make_mock_experiment()
    mock_dataset = _make_mock_dataset()
    test_cases = [_make_test_case()]
    mock_test_results = [mock.Mock(score_results=[])]
    first_score = score_result.ScoreResult(name="accuracy", value=0.4)
    last_score = score_result.ScoreResult(name="accuracy", value=0.9)
    other_score = score_result.ScoreResult(name="precision", value=0.8)
    effective_scores = [last_score, other_score]
    mock_experiment.log_experiment_scores.return_value = effective_scores

    def compute_scores(_):
        return [first_score, last_score, other_score]

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
                                scoring_metrics=[],
                                experiment_scoring_functions=[compute_scores],
                                verbose=0,
                            )

    assert result.experiment_scores == effective_scores
    mock_experiment.log_experiment_scores.assert_called_once_with(
        score_results=effective_scores,
        preserve_unrelated=True,
    )


@pytest.mark.parametrize("invalid_score", [None, object()])
def test_evaluate_experiment__records_invalid_scores(fake_backend, invalid_score):
    mock_experiment = _make_mock_experiment()
    mock_dataset = _make_mock_dataset()
    test_cases = [_make_test_case()]
    mock_test_results = [mock.Mock(score_results=[])]

    def compute_scores(_):
        return [invalid_score]

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
                                scoring_metrics=[],
                                experiment_scoring_functions=[compute_scores],
                                verbose=0,
                            )

    assert len(result.experiment_scores) == 1
    score = result.experiment_scores[0]
    assert score.name == "compute_scores"
    assert score.value == 0.0
    assert score.scoring_failed is True
    assert (
        score.reason
        == f"Experiment scoring function returned {type(invalid_score).__name__}; expected ScoreResult."
    )
    assert score.metadata == {"_fabricated": True}
    mock_experiment.log_experiment_scores.assert_called_once_with(
        score_results=result.experiment_scores,
        preserve_unrelated=False,
    )


def test_evaluate_experiment__deduplicates_duplicate_score_names(fake_backend):
    mock_experiment = _make_mock_experiment()
    mock_dataset = _make_mock_dataset()
    test_cases = [_make_test_case()]
    mock_test_results = [mock.Mock(score_results=[])]

    def scorer_a(_):
        return score_result.ScoreResult(name="accuracy", value=0.5)

    def scorer_b(_):
        return score_result.ScoreResult(name="accuracy", value=0.9)

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
                                scoring_metrics=[],
                                experiment_scoring_functions=[scorer_a, scorer_b],
                                verbose=0,
                            )

    assert len(result.experiment_scores) == 1
    assert result.experiment_scores[0].name == "accuracy"
    assert result.experiment_scores[0].value == 0.9
    mock_experiment.log_experiment_scores.assert_called_once_with(
        score_results=result.experiment_scores,
        preserve_unrelated=True,
    )


@pytest.mark.parametrize(
    "scores, expected",
    [
        (
            [
                score_result.ScoreResult(name="accuracy", value=0.5),
                score_result.ScoreResult(
                    name="accuracy", value=0.0, scoring_failed=True
                ),
            ],
            [score_result.ScoreResult(name="accuracy", value=0.0, scoring_failed=True)],
        ),
        (
            [
                score_result.ScoreResult(
                    name="accuracy", value=0.0, scoring_failed=True
                ),
                score_result.ScoreResult(name="accuracy", value=0.9),
            ],
            [score_result.ScoreResult(name="accuracy", value=0.9)],
        ),
    ],
)
def test_deduplicate_experiment_scores__last_result_wins(scores, expected):
    assert evaluator_module._deduplicate_experiment_scores(scores) == expected


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
