from typing import Dict, Any

import opik
from opik import id_helpers
from opik.evaluation import metrics
from opik.evaluation import evaluator as evaluator_module


def test_evaluate__with_filter_string__filters_dataset_items(
    opik_client: opik.Opik, dataset_name: str, experiment_name: str
):
    """Test that evaluate correctly filters dataset items using filter_string."""
    dataset = opik_client.create_dataset(dataset_name)

    dataset_items = [
        {
            "id": id_helpers.generate_id(),
            "input": {"question": "What is the capital of France?"},
            "expected_model_output": {"output": "Paris"},
            "category": "geography",
        },
        {
            "id": id_helpers.generate_id(),
            "input": {"question": "What is 2+2?"},
            "expected_model_output": {"output": "4"},
            "category": "math",
        },
        {
            "id": id_helpers.generate_id(),
            "input": {"question": "What is the capital of Germany?"},
            "expected_model_output": {"output": "Berlin"},
            "category": "geography",
        },
    ]

    dataset.insert(dataset_items)

    def task(item: Dict[str, Any]):
        if item["input"] == {"question": "What is the capital of France?"}:
            return {"output": "Paris"}
        if item["input"] == {"question": "What is the capital of Germany?"}:
            return {"output": "Berlin"}
        if item["input"] == {"question": "What is 2+2?"}:
            return {"output": "4"}

        raise AssertionError(
            f"Task received dataset item with an unexpected input: {item['input']}"
        )

    equals_metric = metrics.Equals()
    opik.evaluate(
        dataset=dataset,
        task=task,
        scoring_metrics=[equals_metric],
        experiment_name=experiment_name,
        scoring_key_mapping={
            "reference": lambda x: x["expected_model_output"]["output"],
        },
        dataset_filter_string='data.category = "geography"',
    )

    opik.flush_tracker()

    retrieved_experiment = opik_client.get_experiment_by_name(experiment_name)
    experiment_items_contents = retrieved_experiment.get_items()
    assert len(experiment_items_contents) == 2, (
        f"Expected 2 experiment items (filtered by geography category), but got {len(experiment_items_contents)}. "
        f"Experiment items: {experiment_items_contents}"
    )


def test_evaluate_optimization_trial__with_filter_string__filters_dataset_items(
    opik_client: opik.Opik, dataset_name: str, experiment_name: str
):
    """Test that evaluate_optimization_trial correctly filters dataset items using filter_string."""
    dataset = opik_client.create_dataset(dataset_name)

    dataset_items = [
        {
            "id": id_helpers.generate_id(),
            "input": {"question": "What is the capital of France?"},
            "expected_model_output": {"output": "Paris"},
            "category": "geography",
        },
        {
            "id": id_helpers.generate_id(),
            "input": {"question": "What is 2+2?"},
            "expected_model_output": {"output": "4"},
            "category": "math",
        },
        {
            "id": id_helpers.generate_id(),
            "input": {"question": "What is the capital of Germany?"},
            "expected_model_output": {"output": "Berlin"},
            "category": "geography",
        },
    ]

    dataset.insert(dataset_items)

    def task(item: Dict[str, Any]):
        if item["input"] == {"question": "What is the capital of France?"}:
            return {"output": "Paris"}
        if item["input"] == {"question": "What is the capital of Germany?"}:
            return {"output": "Berlin"}
        if item["input"] == {"question": "What is 2+2?"}:
            return {"output": "4"}

        raise AssertionError(
            f"Task received dataset item with an unexpected input: {item['input']}"
        )

    equals_metric = metrics.Equals()
    evaluator_module.evaluate_optimization_trial(
        optimization_id=id_helpers.generate_id(),
        dataset=dataset,
        task=task,
        scoring_metrics=[equals_metric],
        experiment_name=experiment_name,
        scoring_key_mapping={
            "reference": lambda x: x["expected_model_output"]["output"],
        },
        dataset_filter_string='data.category = "math"',
    )

    opik.flush_tracker()

    retrieved_experiment = opik_client.get_experiment_by_name(experiment_name)
    experiment_items_contents = retrieved_experiment.get_items()
    assert len(experiment_items_contents) == 1, (
        f"Expected 1 experiment item (filtered by math category), but got {len(experiment_items_contents)}. "
        f"Experiment items: {experiment_items_contents}"
    )
