import opik
import random
import string

import opik.evaluation
from opik.api_objects.dataset import dataset_item
from opik.evaluation import metrics
import pytest


@pytest.fixture
def experiment_name(opik_client: opik.Opik):
    name = "e2e-tests-experiment-".join(
        random.choice(string.ascii_letters) for _ in range(6)
    )
    yield name


def test_experiment_creation_via_evaluate_function__happyflow(
    opik_client: opik.Opik, dataset_name: str, experiment_name: str
):
    # TODO: this test is not finished, it only checks that the script is not failing

    dataset = opik_client.create_dataset(dataset_name)

    dataset.insert(
        [
            {
                "input": {"question": "What is the of capital of France?"},
                "expected_output": {"output": "Paris"},
            },
            {
                "input": {"question": "What is the of capital of Germany?"},
                "expected_output": {"output": "Berlin"},
            },
            {
                "input": {"question": "What is the of capital of Poland?"},
                "expected_output": {"output": "Warsaw"},
            },
        ]
    )

    def task(item: dataset_item.DatasetItem):
        if item.input == {"question": "What is the of capital of France?"}:
            return {"output": "Paris", "reference": item.expected_output["output"]}
        if item.input == {"question": "What is the of capital of Germany?"}:
            return {"output": "Berlin", "reference": item.expected_output["output"]}
        if item.input == {"question": "What is the of capital of Poland?"}:
            return {"output": "Krakow", "reference": item.expected_output["output"]}

        raise AssertionError(
            f"Task received dataset item with an unexpected input: {item.input}"
        )

    equals_metric = metrics.Equals()
    opik.evaluation.evaluate(
        dataset=dataset,
        task=task,
        scoring_metrics=[equals_metric],
        experiment_name=experiment_name,
    )

    # EXPECTED_DATASET_ITEMS = [
    #     dataset_item.DatasetItem(
    #         input={"question": "What is the of capital of France?"},
    #         expected_output={"output": "Paris"},
    #     ),
    #     dataset_item.DatasetItem(
    #         input={"question": "What is the of capital of Germany?"},
    #         expected_output={"output": "Berlin"},
    #     ),
    #     dataset_item.DatasetItem(
    #         input={"question": "What is the of capital of Poland?"},
    #         expected_output={"output": "Warsaw"},
    #     ),
    # ]
