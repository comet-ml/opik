from typing import Dict, Any, List

import opik
from opik.evaluation import metrics, test_result
from opik.evaluation.metrics import experiment_metric_result

from .. import verifiers
from ...testlib import assert_equal, ANY_BUT_NONE

DATASET_ITEMS = [
    {
        "input": {"question": "What is the capital of Ukraine?"},
        "expected_model_output": {"output": "Kyiv"},
    },
    {
        "input": {"question": "What is the capital of France?"},
        "expected_model_output": {"output": "Paris"},
    },
]


def llm_task(item: Dict[str, Any]):
    if item["input"] == {"question": "What is the capital of Ukraine?"}:
        return {"output": "Kyiv"}
    if item["input"] == {"question": "What is the capital of France?"}:
        return {"output": "Paris"}
    raise AssertionError(
        f"Task received dataset item with an unexpected input: {item['input']}"
    )


def test_evaluate__with_experiment_metrics__happyflow(
    opik_client: opik.Opik, dataset_name: str, experiment_name: str
):
    """Test full e2e flow with experiment metrics and backend upload."""
    dataset = opik_client.create_dataset(dataset_name)
    dataset.insert(DATASET_ITEMS)

    # Create experiment metric function that computes max and min
    def compute_stats(test_results: List[test_result.TestResult]):
        scores = []
        for test_result_item in test_results:
            for score_result in test_result_item.score_results:
                if score_result.name == "equals_metric":
                    scores.append(score_result.value)

        if not scores:
            return []

        return [
            experiment_metric_result.ExperimentMetricResult(
                score_name="equals_metric",
                metric_name="max",
                value=max(scores),
            ),
            experiment_metric_result.ExperimentMetricResult(
                score_name="equals_metric",
                metric_name="min",
                value=min(scores),
            ),
        ]

    evaluation_result = opik.evaluate(
        dataset=dataset,
        task=llm_task,
        scoring_metrics=[metrics.Equals()],
        experiment_name=experiment_name,
        experiment_config={
            "model_name": "gpt-3.5",
        },
        scoring_key_mapping={
            "reference": lambda x: x["expected_model_output"]["output"],
        },
        experiment_metrics=[compute_stats],
    )

    opik.flush_tracker()

    # Verify experiment was created correctly
    verifiers.verify_experiment(
        opik_client=opik_client,
        id=evaluation_result.experiment_id,
        experiment_name=evaluation_result.experiment_name,
        experiment_metadata={"model_name": "gpt-3.5"},
        traces_amount=2,  # two traces (one per dataset item)
        feedback_scores_amount=1,
        prompts=None,
    )

    assert evaluation_result.dataset_id == dataset.id

    # Verify experiment metrics were uploaded to backend
    retrieved_experiment = opik_client.get_experiment_by_id(
        evaluation_result.experiment_id
    )
    assert retrieved_experiment.pre_computed_metric_aggregates is not None
    assert "equals_metric" in retrieved_experiment.pre_computed_metric_aggregates
    aggregates = retrieved_experiment.pre_computed_metric_aggregates["equals_metric"]
    assert "max" in aggregates
    assert "min" in aggregates
    # Both items should have score 1.0 (correct answers)
    assert aggregates["max"] == 1.0
    assert aggregates["min"] == 1.0


def test_evaluate_prompt__with_experiment_metrics__happyflow(
    opik_client: opik.Opik, dataset_name: str, experiment_name: str
):
    """Test evaluate_prompt with experiment metrics."""
    dataset = opik_client.create_dataset(dataset_name)
    dataset.insert(DATASET_ITEMS)

    # Create experiment metric function that computes average
    def compute_avg_metric(test_results: List[test_result.TestResult]):
        scores = []
        for test_result_item in test_results:
            for score_result in test_result_item.score_results:
                if score_result.name == "equals_metric":
                    scores.append(score_result.value)

        if not scores:
            return experiment_metric_result.ExperimentMetricResult(
                score_name="equals_metric",
                metric_name="avg",
                value=0.0,
            )

        avg_value = sum(scores) / len(scores)
        return experiment_metric_result.ExperimentMetricResult(
            score_name="equals_metric",
            metric_name="avg",
            value=avg_value,
        )

    evaluation_result = opik.evaluate_prompt(
        dataset=dataset,
        messages=[{"role": "user", "content": "{{input.question}}"}],
        model="gpt-3.5-turbo",
        scoring_metrics=[metrics.Equals()],
        experiment_name=experiment_name,
        scoring_key_mapping={
            "reference": lambda x: x["expected_model_output"]["output"],
        },
        experiment_metrics=[compute_avg_metric],
    )

    opik.flush_tracker()

    # Verify experiment was created correctly
    verifiers.verify_experiment(
        opik_client=opik_client,
        id=evaluation_result.experiment_id,
        experiment_name=evaluation_result.experiment_name,
        traces_amount=2,  # two traces (one per dataset item)
        feedback_scores_amount=1,
        prompts=None,
    )

    assert evaluation_result.dataset_id == dataset.id

    # Verify experiment metrics were uploaded to backend
    retrieved_experiment = opik_client.get_experiment_by_id(
        evaluation_result.experiment_id
    )
    assert retrieved_experiment.pre_computed_metric_aggregates is not None
    assert "equals_metric" in retrieved_experiment.pre_computed_metric_aggregates
    aggregates = retrieved_experiment.pre_computed_metric_aggregates["equals_metric"]
    assert "avg" in aggregates
    # Both items should have score 1.0 (correct answers), so avg = 1.0
    assert aggregates["avg"] == 1.0

