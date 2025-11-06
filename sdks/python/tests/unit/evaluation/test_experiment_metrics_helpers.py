from unittest import mock
import pytest

from opik.evaluation import evaluation_result, test_result, test_case
from opik.evaluation.metrics import experiment_metric_result, score_result

# Import experiment_metrics_helpers using the same pattern as evaluator.py
import opik.evaluation.experiment_metrics_helpers as experiment_metrics_helpers


def test_compute_experiment_metrics__single_result__happyflow():
    """Test that a function returning a single ExperimentMetricResult is handled correctly."""
    # Create a mock evaluation result
    eval_result = evaluation_result.EvaluationResult(
        experiment_id="exp1",
        dataset_id="dataset1",
        experiment_name="Test Experiment",
        test_results=[],
        experiment_url="http://test.com",
        trial_count=1,
    )

    # Create a metric function that returns a single result
    def compute_max_metric(evaluation_result_: evaluation_result.EvaluationResult):
        return experiment_metric_result.ExperimentMetricResult(
            score_name="equals_metric",
            metric_name="max",
            value=1.0,
        )

    # Compute metrics
    result = experiment_metrics_helpers.compute_experiment_metrics(
        experiment_metrics=[compute_max_metric],
        evaluation_result_=eval_result,
    )

    # Verify result format
    assert isinstance(result, dict)
    assert "equals_metric" in result
    assert "max" in result["equals_metric"]
    assert result["equals_metric"]["max"] == 1.0


def test_compute_experiment_metrics__list_of_results__happyflow():
    """Test that a function returning a list of ExperimentMetricResult is handled correctly."""
    # Create a mock evaluation result
    eval_result = evaluation_result.EvaluationResult(
        experiment_id="exp1",
        dataset_id="dataset1",
        experiment_name="Test Experiment",
        test_results=[],
        experiment_url="http://test.com",
        trial_count=1,
    )

    # Create a metric function that returns a list of results
    def compute_stats(evaluation_result_: evaluation_result.EvaluationResult):
        return [
            experiment_metric_result.ExperimentMetricResult(
                score_name="equals_metric",
                metric_name="max",
                value=1.0,
            ),
            experiment_metric_result.ExperimentMetricResult(
                score_name="equals_metric",
                metric_name="min",
                value=0.0,
            ),
            experiment_metric_result.ExperimentMetricResult(
                score_name="equals_metric",
                metric_name="avg",
                value=0.5,
            ),
        ]

    # Compute metrics
    result = experiment_metrics_helpers.compute_experiment_metrics(
        experiment_metrics=[compute_stats],
        evaluation_result_=eval_result,
    )

    # Verify result format
    assert isinstance(result, dict)
    assert "equals_metric" in result
    assert result["equals_metric"]["max"] == 1.0
    assert result["equals_metric"]["min"] == 0.0
    assert result["equals_metric"]["avg"] == 0.5


def test_compute_experiment_metrics__function_raises_exception__error_logged_but_continues():
    """Test that exceptions in metric functions are logged but don't crash the computation."""
    # Create a mock evaluation result
    eval_result = evaluation_result.EvaluationResult(
        experiment_id="exp1",
        dataset_id="dataset1",
        experiment_name="Test Experiment",
        test_results=[],
        experiment_url="http://test.com",
        trial_count=1,
    )

    # Create a metric function that raises an exception
    def failing_metric(evaluation_result_: evaluation_result.EvaluationResult):
        raise ValueError("Test error")

    # Create a working metric function
    def working_metric(evaluation_result_: evaluation_result.EvaluationResult):
        return experiment_metric_result.ExperimentMetricResult(
            score_name="equals_metric",
            metric_name="max",
            value=1.0,
        )

    # Mock the logger to verify error is logged
    with mock.patch.object(
        experiment_metrics_helpers.LOGGER, "error"
    ) as mock_error:
        # Compute metrics - should not raise exception
        result = experiment_metrics_helpers.compute_experiment_metrics(
            experiment_metrics=[failing_metric, working_metric],
            evaluation_result_=eval_result,
        )

        # Verify error was logged
        mock_error.assert_called_once()
        assert "Failed to compute experiment metric" in str(mock_error.call_args)

        # Verify that working metric still computed
        assert isinstance(result, dict)
        assert "equals_metric" in result
        assert result["equals_metric"]["max"] == 1.0

