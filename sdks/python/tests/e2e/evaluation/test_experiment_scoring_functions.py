import functools
import statistics
from typing import Any, Dict, List

import pytest
from sklearn import metrics as sklearn_metrics

import opik
from opik.evaluation import metrics
from opik.evaluation import test_result
from opik.evaluation.metrics import score_result
from opik.evaluation.scoring_functions import (
    classification_f1_score,
    classification_precision_score,
    classification_recall_score,
)
from .. import verifiers
from ...testlib import generate_project_name

PROJECT_NAME = generate_project_name("e2e", __name__)


def test_experiment_scoring_functions__standard_deviation__computed_and_logged(
    opik_client: opik.Opik, dataset_name: str, experiment_name: str
):
    """Test that experiment scoring functions can compute and log standard deviation of metric scores."""
    dataset = opik_client.create_dataset(dataset_name, project_name=PROJECT_NAME)

    dataset.insert(
        [
            {
                "input": {"question": "What is the capital of France?"},
                "expected_output": "Paris",
            },
            {
                "input": {"question": "What is the capital of Poland?"},
                "expected_output": "Warsaw",
            },
        ]
    )

    def task(item: Dict[str, Any]):
        # Return correct answer for France, wrong answer for Poland so
        # metric scores are [1.0, 0.0] — enough to exercise stdev.
        if item["input"] == {"question": "What is the capital of France?"}:
            return {"output": "Paris", "reference": item["expected_output"]}
        if item["input"] == {"question": "What is the capital of Poland?"}:
            return {"output": "Krakow", "reference": item["expected_output"]}

        raise AssertionError(
            f"Task received dataset item with an unexpected input: {item['input']}"
        )

    def compute_std_deviation(
        test_results: List[test_result.TestResult],
    ) -> List[score_result.ScoreResult]:
        """Compute standard deviation of metric scores across all test results."""
        scores = [x.score_results[0].value for x in test_results]
        return [
            score_result.ScoreResult(
                name="equals_metric_std_dev",
                value=statistics.stdev(scores) if len(scores) > 1 else 0.0,
                reason=f"Standard deviation of {len(scores)} metric scores",
            )
        ]

    equals_metric = metrics.Equals()
    evaluation_result = opik.evaluate(
        dataset=dataset,
        task=task,
        scoring_metrics=[equals_metric],
        experiment_name=experiment_name,
        experiment_config={
            "model_name": "test-model",
        },
        experiment_scoring_functions=[compute_std_deviation],
        project_name=PROJECT_NAME,
    )

    # Verify experiment scores are in the result
    assert len(evaluation_result.experiment_scores) == 1, (
        f"Expected 1 experiment score in evaluation result, but got {len(evaluation_result.experiment_scores)}. "
        f"Experiment scores: {evaluation_result.experiment_scores}"
    )
    assert evaluation_result.experiment_scores[0].name == "equals_metric_std_dev", (
        f"Expected experiment score name 'equals_metric_std_dev', but got '{evaluation_result.experiment_scores[0].name}'. "
        f"Full score object: {evaluation_result.experiment_scores[0]}"
    )

    expected_stdev = statistics.stdev([1.0, 0.0])
    assert evaluation_result.experiment_scores[0].value == pytest.approx(
        expected_stdev, abs=0.0001
    ), (
        f"Expected experiment score value {expected_stdev}, but got {evaluation_result.experiment_scores[0].value}. "
        f"Full score object: {evaluation_result.experiment_scores[0]}"
    )
    assert evaluation_result.experiment_scores[0].reason is not None, (
        f"Expected experiment score reason to be set, but got None. "
        f"Score: {evaluation_result.experiment_scores[0]}"
    )
    assert (
        "Standard deviation of 2 metric scores"
        in evaluation_result.experiment_scores[0].reason
    ), (
        f"Expected reason to contain 'Standard deviation of 2 metric scores', but got: '{evaluation_result.experiment_scores[0].reason}'"
    )

    # Verify experiment was created
    verifiers.verify_experiment(
        opik_client=opik_client,
        id=evaluation_result.experiment_id,
        experiment_name=evaluation_result.experiment_name,
        experiment_metadata={"model_name": "test-model"},
        traces_amount=2,  # one trace per dataset item
        feedback_scores_amount=1,
        project_name=PROJECT_NAME,
    )

    # Verify experiment scores are logged to backend by retrieving the experiment
    retrieved_experiment = opik_client.get_experiment_by_id(
        evaluation_result.experiment_id
    )
    experiment_data = retrieved_experiment.get_experiment_data()

    # Check that experiment scores are present in the experiment data
    assert experiment_data.experiment_scores is not None, (
        f"Expected experiment_scores to be set in experiment data, but got None. "
        f"Experiment ID: {evaluation_result.experiment_id}, "
        f"Experiment name: {evaluation_result.experiment_name}, "
        f"Experiment data: {experiment_data}"
    )
    assert len(experiment_data.experiment_scores) == 1, (
        f"Expected 1 experiment score in backend, but got {len(experiment_data.experiment_scores) if experiment_data.experiment_scores else 0}. "
        f"Experiment scores: {experiment_data.experiment_scores}"
    )
    assert experiment_data.experiment_scores[0].name == "equals_metric_std_dev", (
        f"Expected experiment score name 'equals_metric_std_dev' in backend, but got '{experiment_data.experiment_scores[0].name}'. "
        f"Full score object: {experiment_data.experiment_scores[0]}"
    )
    assert experiment_data.experiment_scores[0].value == pytest.approx(
        expected_stdev, abs=0.0001
    ), (
        f"Expected experiment score value {expected_stdev} in backend, but got {experiment_data.experiment_scores[0].value}. "
        f"Full score object: {experiment_data.experiment_scores[0]}"
    )


def test_experiment_scoring_functions__classification_metrics__computed_and_logged(
    opik_client: opik.Opik, dataset_name: str, experiment_name: str
):
    dataset = opik_client.create_dataset(dataset_name, project_name=PROJECT_NAME)

    dataset.insert(
        [
            {"input": {"text": "first"}, "reference": "cat"},
            {"input": {"text": "second"}, "reference": "dog"},
            {"input": {"text": "third"}, "reference": "cat"},
        ]
    )

    def task(item: Dict[str, Any]):
        if item["input"] == {"text": "first"}:
            return {"output": "cat"}
        if item["input"] == {"text": "second"}:
            return {"output": "cat"}
        if item["input"] == {"text": "third"}:
            return {"output": "cat"}

        raise AssertionError(
            f"Task received dataset item with an unexpected input: {item['input']}"
        )

    average = "macro"
    f1_fn = functools.partial(classification_f1_score, average=average)
    precision_fn = functools.partial(classification_precision_score, average=average)
    recall_fn = functools.partial(classification_recall_score, average=average)

    evaluation_result = opik.evaluate(
        dataset=dataset,
        task=task,
        scoring_metrics=[],
        experiment_name=experiment_name,
        experiment_config={"model_name": "test-model"},
        experiment_scoring_functions=[f1_fn, precision_fn, recall_fn],
        project_name=PROJECT_NAME,
    )

    expected_y_true = ["cat", "dog", "cat"]
    expected_y_pred = ["cat", "cat", "cat"]
    expected_f1 = sklearn_metrics.f1_score(
        expected_y_true,
        expected_y_pred,
        average=average,
    )
    expected_precision = sklearn_metrics.precision_score(
        expected_y_true,
        expected_y_pred,
        average=average,
    )
    expected_recall = sklearn_metrics.recall_score(
        expected_y_true,
        expected_y_pred,
        average=average,
    )

    assert len(evaluation_result.experiment_scores) == 3, (
        f"Expected 3 experiment scores, got {len(evaluation_result.experiment_scores)}. "
        f"Scores: {evaluation_result.experiment_scores}"
    )

    scores_by_name = {
        score.name: score for score in evaluation_result.experiment_scores
    }
    assert scores_by_name["classification_f1_macro"].value == pytest.approx(
        expected_f1, abs=0.0001
    )
    assert scores_by_name["classification_precision_macro"].value == pytest.approx(
        expected_precision, abs=0.0001
    )
    assert scores_by_name["classification_recall_macro"].value == pytest.approx(
        expected_recall, abs=0.0001
    )

    retrieved_experiment = opik_client.get_experiment_by_id(
        evaluation_result.experiment_id
    )
    experiment_data = retrieved_experiment.get_experiment_data()
    assert experiment_data.experiment_scores is not None
    assert len(experiment_data.experiment_scores) == 3
