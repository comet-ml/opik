import opik

from opik.api_objects.dataset import dataset_item
from opik.evaluation import metrics
from . import verifiers


def test_experiment_creation_via_evaluate_function__happyflow(
    opik_client: opik.Opik, dataset_name: str, experiment_name: str
):
    # TODO: this test is not finished, it does not check experiment items content
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
    evaluation_result = opik.evaluate(
        dataset=dataset,
        task=task,
        scoring_metrics=[equals_metric],
        experiment_name=experiment_name,
        experiment_config={"model_name": "gpt-3.5"},
    )

    opik.flush_tracker()

    verifiers.verify_experiment(
        opik_client=opik_client,
        id=evaluation_result.experiment_id,
        experiment_name=evaluation_result.experiment_name,
        experiment_metadata={"model_name": "gpt-3.5"},
        traces_amount=3,  # one trace per dataset item
        feedback_scores_amount=1,  # an average value of all Equals metric scores
    )

    # TODO: check more content of the experiment
    #
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
