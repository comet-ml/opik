from typing import Dict, Any, List

import opik
from opik.evaluation import metrics, test_result
from opik.evaluation.metrics import experiment_metric_result

from .. import verifiers

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
        return {"output": "London"}
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
        feedback_scores_amount=3,
        prompts=None,
    )

    assert evaluation_result.dataset_id == dataset.id

    # Verify experiment metrics were uploaded to backend
    retrieved_experiment = opik_client.get_experiment_by_id(
        evaluation_result.experiment_id
    )
    experiment_data = retrieved_experiment.get_experiment_data()
    assert experiment_data.pre_computed_metric_aggregates is not None
    assert "equals_metric" in retrieved_experiment.get_experiment_data().pre_computed_metric_aggregates
    aggregates = retrieved_experiment.get_experiment_data().pre_computed_metric_aggregates["equals_metric"]
    assert "max" in aggregates
    assert "min" in aggregates
    # Both items should have score 1.0 (correct answers)
    assert aggregates["max"] == 1.0
    assert aggregates["min"] == 0.0
