"""
Example demonstrating how to use experiment-level metrics in Opik.

Experiment metrics are computed after all test cases are evaluated and provide
aggregate statistics across the entire experiment. This is useful for computing
things like max, min, avg, median, or custom aggregations.
"""

from typing import Dict, Any, List

from opik.evaluation.metrics import Equals, ExperimentMetricResult
from opik.evaluation import evaluate, TestResult
from opik import Opik, track


def compute_accuracy_stats(
    test_results: List[TestResult],
) -> List[ExperimentMetricResult]:
    """
    Compute experiment-level statistics for the Equals metric (accuracy).

    Returns max, min, and avg accuracy across all test results.
    """
    accuracy_scores = []

    for test_result_item in test_results:
        for score_result in test_result_item.score_results:
            if score_result.name == "equals_metric":
                accuracy_scores.append(score_result.value)

    if not accuracy_scores:
        return []

    return [
        ExperimentMetricResult(
            score_name="equals_metric",
            metric_name="max",
            value=max(accuracy_scores),
        ),
        ExperimentMetricResult(
            score_name="equals_metric",
            metric_name="min",
            value=min(accuracy_scores),
        ),
        ExperimentMetricResult(
            score_name="equals_metric",
            metric_name="avg",
            value=sum(accuracy_scores) / len(accuracy_scores),
        ),
    ]


def compute_pass_rate(
    test_results: List[TestResult],
) -> ExperimentMetricResult:
    """
    Compute the pass rate (percentage of test cases with accuracy = 1.0).

    Returns a single ExperimentMetricResult.
    """
    total_count = 0
    pass_count = 0

    for test_result_item in test_results:
        for score_result in test_result_item.score_results:
            if score_result.name == "equals_metric":
                total_count += 1
                if score_result.value == 1.0:
                    pass_count += 1

    if total_count == 0:
        pass_rate = 0.0
    else:
        pass_rate = pass_count / total_count

    return ExperimentMetricResult(
        score_name="equals_metric",
        metric_name="pass_rate",
        value=pass_rate,
    )


# Create a simple task for testing
@track()
def simple_task(item: Dict[str, Any]) -> Dict[str, Any]:
    """A simple task that echoes back the input."""
    return {
        "output": item["input"],
        "reference": item.get("expected_output", item["input"]),
    }


if __name__ == "__main__":
    # Create client and dataset
    client = Opik()
    dataset = client.get_or_create_dataset(
        name="Experiment Metrics Example",
        description="Example dataset for testing experiment-level metrics",
    )

    # Insert some sample data
    dataset.insert(
        [
            {"input": "hello", "expected_output": "hello"},
            {"input": "world", "expected_output": "world"},
            {"input": "test", "expected_output": "different"},  # This will fail
            {"input": "opik", "expected_output": "opik"},
        ]
    )

    # Run evaluation with experiment metrics
    results = evaluate(
        experiment_name="Experiment Metrics Demo",
        dataset=dataset,
        task=simple_task,
        scoring_metrics=[Equals()],
        experiment_metrics=[
            compute_accuracy_stats,  # Returns a list of results
            compute_pass_rate,  # Returns a single result
        ],
        verbose=2,
    )

    print("\n" + "=" * 60)
    print("Evaluation completed!")
    print(f"Total test results: {len(results.test_results)}")
    print(f"Experiment ID: {results.experiment_id}")
    print(f"Experiment URL: {results.experiment_url}")
    print("=" * 60)
    print("\nExperiment-level metrics have been uploaded to the backend.")
    print("Check the UI to see max, min, avg, and pass_rate metrics!")
