from typing import Dict, Any

import opik

from opik import Prompt, synchronization, exceptions, id_helpers
from opik.api_objects.dataset import dataset_item
from opik.evaluation import metrics
from opik.api_objects.experiment import experiment_item
from . import verifiers
from ..conftest import random_chars
from ..testlib import assert_equal, ANY_BUT_NONE

import pytest


def test_experiment_creation_via_evaluate_function__single_prompt_arg_used__happyflow(
    opik_client: opik.Opik, dataset_name: str, experiment_name: str
):
    dataset = opik_client.create_dataset(dataset_name)

    dataset.insert(
        [
            {
                "input": {"question": "What is the of capital of France?"},
                "expected_model_output": {"output": "Paris"},
            },
            {
                "input": {"question": "What is the of capital of Germany?"},
                "expected_model_output": {"output": "Berlin"},
            },
            {
                "input": {"question": "What is the of capital of Poland?"},
                "expected_model_output": {"output": "Warsaw"},
            },
        ]
    )

    def task(item: Dict[str, Any]):
        if item["input"] == {"question": "What is the of capital of France?"}:
            return {"output": "Paris"}
        if item["input"] == {"question": "What is the of capital of Germany?"}:
            return {"output": "Berlin"}
        if item["input"] == {"question": "What is the of capital of Poland?"}:
            return {"output": "Krakow"}

        raise AssertionError(
            f"Task received dataset item with an unexpected input: {item['input']}"
        )

    prompt = Prompt(
        name=f"test-experiment-prompt-{random_chars()}",
        prompt=f"test-experiment-prompt-template-{random_chars()}",
    )

    equals_metric = metrics.Equals()
    evaluation_result = opik.evaluate(
        dataset=dataset,
        task=task,
        scoring_metrics=[equals_metric],
        experiment_name=experiment_name,
        experiment_config={
            "model_name": "gpt-3.5",
        },
        scoring_key_mapping={
            "reference": lambda x: x["expected_model_output"]["output"],
        },
        prompt=prompt,
    )

    opik.flush_tracker()

    verifiers.verify_experiment(
        opik_client=opik_client,
        id=evaluation_result.experiment_id,
        experiment_name=evaluation_result.experiment_name,
        experiment_metadata={"model_name": "gpt-3.5"},
        traces_amount=3,  # one trace per dataset item
        feedback_scores_amount=1,
        prompts=[prompt],
    )

    retrieved_experiment = opik_client.get_experiment_by_name(experiment_name)
    experiment_items_contents = retrieved_experiment.get_items()
    assert len(experiment_items_contents) == 3

    EXPECTED_EXPERIMENT_ITEMS_CONTENT = [
        experiment_item.ExperimentItemContent(
            id=ANY_BUT_NONE,
            dataset_item_id=ANY_BUT_NONE,
            trace_id=ANY_BUT_NONE,
            dataset_item_data={
                "input": {"question": "What is the of capital of France?"},
                "expected_model_output": {"output": "Paris"},
            },
            evaluation_task_output={"output": "Paris"},
            feedback_scores=[
                {
                    "category_name": None,
                    "name": "equals_metric",
                    "reason": None,
                    "value": 1.0,
                }
            ],
        ),
        experiment_item.ExperimentItemContent(
            id=ANY_BUT_NONE,
            dataset_item_id=ANY_BUT_NONE,
            trace_id=ANY_BUT_NONE,
            dataset_item_data={
                "input": {"question": "What is the of capital of Germany?"},
                "expected_model_output": {"output": "Berlin"},
            },
            evaluation_task_output={"output": "Berlin"},
            feedback_scores=[
                {
                    "category_name": None,
                    "name": "equals_metric",
                    "reason": None,
                    "value": 1.0,
                }
            ],
        ),
        experiment_item.ExperimentItemContent(
            id=ANY_BUT_NONE,
            dataset_item_id=ANY_BUT_NONE,
            trace_id=ANY_BUT_NONE,
            dataset_item_data={
                "input": {"question": "What is the of capital of Poland?"},
                "expected_model_output": {"output": "Warsaw"},
            },
            evaluation_task_output={"output": "Krakow"},
            feedback_scores=[
                {
                    "category_name": None,
                    "name": "equals_metric",
                    "reason": None,
                    "value": 0.0,
                }
            ],
        ),
    ]
    assert_equal(
        sorted(
            EXPECTED_EXPERIMENT_ITEMS_CONTENT,
            key=lambda item: str(item.dataset_item_data),
        ),
        sorted(experiment_items_contents, key=lambda item: str(item.dataset_item_data)),
    )


def test_experiment_creation_via_evaluate_function__single_prompt_arg_used__filter_dataset_items_by_id(
    opik_client: opik.Opik, dataset_name: str, experiment_name: str
):
    dataset = opik_client.create_dataset(dataset_name)

    dataset_items = [
        {
            "id": id_helpers.generate_id(),
            "input": {"question": "What is the of capital of France?"},
            "expected_model_output": {"output": "Paris"},
        },
        {
            "id": id_helpers.generate_id(),
            "input": {"question": "What is the of capital of Germany?"},
            "expected_model_output": {"output": "Berlin"},
        },
        {
            "id": id_helpers.generate_id(),
            "input": {"question": "What is the of capital of Poland?"},
            "expected_model_output": {"output": "Warsaw"},
        },
    ]

    dataset.insert(dataset_items)

    def task(item: Dict[str, Any]):
        if item["input"] == {"question": "What is the of capital of France?"}:
            return {"output": "Paris"}
        if item["input"] == {"question": "What is the of capital of Germany?"}:
            return {"output": "Berlin"}
        if item["input"] == {"question": "What is the of capital of Poland?"}:
            return {"output": "Krakow"}

        raise AssertionError(
            f"Task received dataset item with an unexpected input: {item['input']}"
        )

    prompt = Prompt(
        name=f"test-experiment-prompt-{random_chars()}",
        prompt=f"test-experiment-prompt-template-{random_chars()}",
    )

    dataset_item_ids = [item["id"] for item in dataset_items]
    dataset_item_ids.pop(2)
    # add non existing id
    dataset_item_ids.append(id_helpers.generate_id())

    equals_metric = metrics.Equals()
    evaluation_result = opik.evaluate(
        dataset=dataset,
        task=task,
        scoring_metrics=[equals_metric],
        experiment_name=experiment_name,
        experiment_config={
            "model_name": "gpt-3.5",
        },
        scoring_key_mapping={
            "reference": lambda x: x["expected_model_output"]["output"],
        },
        prompt=prompt,
        dataset_item_ids=dataset_item_ids,
    )

    opik.flush_tracker()

    verifiers.verify_experiment(
        opik_client=opik_client,
        id=evaluation_result.experiment_id,
        experiment_name=evaluation_result.experiment_name,
        experiment_metadata={"model_name": "gpt-3.5"},
        traces_amount=2,  # one trace per dataset item
        feedback_scores_amount=1,
        prompts=[prompt],
    )

    retrieved_experiment = opik_client.get_experiment_by_name(experiment_name)
    experiment_items_contents = retrieved_experiment.get_items()
    assert len(experiment_items_contents) == 2

    EXPECTED_EXPERIMENT_ITEMS_CONTENT = [
        experiment_item.ExperimentItemContent(
            id=ANY_BUT_NONE,
            dataset_item_id=ANY_BUT_NONE,
            trace_id=ANY_BUT_NONE,
            dataset_item_data={
                "input": {"question": "What is the of capital of France?"},
                "expected_model_output": {"output": "Paris"},
            },
            evaluation_task_output={"output": "Paris"},
            feedback_scores=[
                {
                    "category_name": None,
                    "name": "equals_metric",
                    "reason": None,
                    "value": 1.0,
                }
            ],
        ),
        experiment_item.ExperimentItemContent(
            id=ANY_BUT_NONE,
            dataset_item_id=ANY_BUT_NONE,
            trace_id=ANY_BUT_NONE,
            dataset_item_data={
                "input": {"question": "What is the of capital of Germany?"},
                "expected_model_output": {"output": "Berlin"},
            },
            evaluation_task_output={"output": "Berlin"},
            feedback_scores=[
                {
                    "category_name": None,
                    "name": "equals_metric",
                    "reason": None,
                    "value": 1.0,
                }
            ],
        ),
    ]
    assert_equal(
        sorted(
            EXPECTED_EXPERIMENT_ITEMS_CONTENT,
            key=lambda item: str(item.dataset_item_data),
        ),
        sorted(experiment_items_contents, key=lambda item: str(item.dataset_item_data)),
    )


def test_experiment_creation_via_evaluate_function__multiple_prompts_arg_used__happyflow(
    opik_client: opik.Opik, dataset_name: str, experiment_name: str
):
    dataset = opik_client.create_dataset(dataset_name)

    dataset.insert(
        [
            {
                "input": {"question": "What is the of capital of France?"},
                "expected_model_output": {"output": "Paris"},
            },
            {
                "input": {"question": "What is the of capital of Germany?"},
                "expected_model_output": {"output": "Berlin"},
            },
            {
                "input": {"question": "What is the of capital of Poland?"},
                "expected_model_output": {"output": "Warsaw"},
            },
        ]
    )

    def task(item: Dict[str, Any]):
        if item["input"] == {"question": "What is the of capital of France?"}:
            return {"output": "Paris"}
        if item["input"] == {"question": "What is the of capital of Germany?"}:
            return {"output": "Berlin"}
        if item["input"] == {"question": "What is the of capital of Poland?"}:
            return {"output": "Krakow"}

        raise AssertionError(
            f"Task received dataset item with an unexpected input: {item['input']}"
        )

    prompt1 = Prompt(
        name=f"test-experiment-prompt-{random_chars()}",
        prompt=f"test-experiment-prompt-template-{random_chars()}",
    )
    prompt2 = Prompt(
        name=f"test-experiment-prompt-{random_chars()}",
        prompt=f"test-experiment-prompt-template-{random_chars()}",
    )

    equals_metric = metrics.Equals()
    evaluation_result = opik.evaluate(
        dataset=dataset,
        task=task,
        scoring_metrics=[equals_metric],
        experiment_name=experiment_name,
        experiment_config={
            "model_name": "gpt-3.5",
        },
        scoring_key_mapping={
            "reference": lambda x: x["expected_model_output"]["output"],
        },
        prompts=[prompt1, prompt2],
    )

    opik.flush_tracker()

    verifiers.verify_experiment(
        opik_client=opik_client,
        id=evaluation_result.experiment_id,
        experiment_name=evaluation_result.experiment_name,
        experiment_metadata={"model_name": "gpt-3.5"},
        traces_amount=3,  # one trace per dataset item
        feedback_scores_amount=1,
        prompts=[prompt1, prompt2],
    )

    retrieved_experiment = opik_client.get_experiment_by_name(experiment_name)
    experiment_items_contents = retrieved_experiment.get_items()
    assert len(experiment_items_contents) == 3

    EXPECTED_EXPERIMENT_ITEMS_CONTENT = [
        experiment_item.ExperimentItemContent(
            id=ANY_BUT_NONE,
            dataset_item_id=ANY_BUT_NONE,
            trace_id=ANY_BUT_NONE,
            dataset_item_data={
                "input": {"question": "What is the of capital of France?"},
                "expected_model_output": {"output": "Paris"},
            },
            evaluation_task_output={"output": "Paris"},
            feedback_scores=[
                {
                    "category_name": None,
                    "name": "equals_metric",
                    "reason": None,
                    "value": 1.0,
                }
            ],
        ),
        experiment_item.ExperimentItemContent(
            id=ANY_BUT_NONE,
            dataset_item_id=ANY_BUT_NONE,
            trace_id=ANY_BUT_NONE,
            dataset_item_data={
                "input": {"question": "What is the of capital of Germany?"},
                "expected_model_output": {"output": "Berlin"},
            },
            evaluation_task_output={"output": "Berlin"},
            feedback_scores=[
                {
                    "category_name": None,
                    "name": "equals_metric",
                    "reason": None,
                    "value": 1.0,
                }
            ],
        ),
        experiment_item.ExperimentItemContent(
            id=ANY_BUT_NONE,
            dataset_item_id=ANY_BUT_NONE,
            trace_id=ANY_BUT_NONE,
            dataset_item_data={
                "input": {"question": "What is the of capital of Poland?"},
                "expected_model_output": {"output": "Warsaw"},
            },
            evaluation_task_output={"output": "Krakow"},
            feedback_scores=[
                {
                    "category_name": None,
                    "name": "equals_metric",
                    "reason": None,
                    "value": 0.0,
                }
            ],
        ),
    ]
    assert_equal(
        sorted(
            EXPECTED_EXPERIMENT_ITEMS_CONTENT,
            key=lambda item: str(item.dataset_item_data),
        ),
        sorted(experiment_items_contents, key=lambda item: str(item.dataset_item_data)),
    )


def test_experiment_creation__experiment_config_not_set__None_metadata_sent_to_backend(
    opik_client: opik.Opik, dataset_name: str, experiment_name: str
):
    dataset = opik_client.create_dataset(dataset_name)

    dataset.insert(
        [
            {
                "input": {"question": "What is the of capital of France?"},
                "reference": "Paris",
            },
        ]
    )

    def task(item: dataset_item.DatasetItem):
        if item["input"] == {"question": "What is the of capital of France?"}:
            return {
                "output": "Paris",
            }

        raise AssertionError(
            f"Task received dataset item with an unexpected input: {item['input']}"
        )

    equals_metric = metrics.Equals()
    evaluation_result = opik.evaluate(
        dataset=dataset,
        task=task,
        scoring_metrics=[equals_metric],
        experiment_name=experiment_name,
    )

    opik.flush_tracker()

    verifiers.verify_experiment(
        opik_client=opik_client,
        id=evaluation_result.experiment_id,
        experiment_name=evaluation_result.experiment_name,
        experiment_metadata=None,
        traces_amount=1,  # one trace per dataset item
        feedback_scores_amount=1,
    )


def test_experiment_creation__name_can_be_omitted(
    opik_client: opik.Opik, dataset_name: str, experiment_name: str
):
    """
    We can send "None" as experiment_name and the backend will set it for us
    """
    dataset = opik_client.create_dataset(dataset_name)

    dataset.insert(
        [
            {
                "input": {"question": "What is the of capital of France?"},
                "reference": "Paris",
            },
        ]
    )

    def task(item: dataset_item.DatasetItem):
        if item["input"] == {"question": "What is the of capital of France?"}:
            return {"output": "Paris"}

        raise AssertionError(
            f"Task received dataset item with an unexpected input: {item['input']}"
        )

    equals_metric = metrics.Equals()
    evaluation_result = opik.evaluate(
        dataset=dataset,
        task=task,
        scoring_metrics=[equals_metric],
        experiment_name=None,
    )

    opik.flush_tracker()

    experiment_id = evaluation_result.experiment_id

    if not synchronization.until(
        lambda: (
            opik_client._rest_client.experiments.get_experiment_by_id(experiment_id)
            is not None
        ),
        allow_errors=True,
    ):
        raise AssertionError(f"Failed to get experiment with id {experiment_id}.")

    experiment_content = opik_client._rest_client.experiments.get_experiment_by_id(
        experiment_id
    )

    assert experiment_content.name is not None


def test_experiment_creation__scoring_metrics_not_set(
    opik_client: opik.Opik, dataset_name: str, experiment_name
):
    """
    We can create an experiment without scoring metrics
    """
    dataset = opik_client.create_dataset(dataset_name)

    dataset.insert(
        [
            {
                "input": {"question": "What is the of capital of France?"},
                "expected_model_output": {"output": "Paris"},
            },
        ]
    )

    def task(item: dataset_item.DatasetItem):
        if item["input"] == {"question": "What is the of capital of France?"}:
            return {
                "output": "Paris",
            }

        raise AssertionError(
            f"Task received dataset item with an unexpected input: {item['input']}"
        )

    evaluation_result = opik.evaluate(
        dataset=dataset,
        task=task,
        experiment_name=experiment_name,
    )

    opik.flush_tracker()

    experiment_id = evaluation_result.experiment_id

    if not synchronization.until(
        lambda: (
            opik_client._rest_client.experiments.get_experiment_by_id(experiment_id)
            is not None
        ),
        allow_errors=True,
    ):
        raise AssertionError(f"Failed to get experiment with id {experiment_id}.")

    verifiers.verify_experiment(
        opik_client=opik_client,
        id=evaluation_result.experiment_id,
        experiment_name=evaluation_result.experiment_name,
        experiment_metadata=None,
        traces_amount=1,
        feedback_scores_amount=0,
    )


def test_evaluate_experiment__an_experiment_created_with_evaluate__then_new_scores_are_added_to_existing_experiment_items__amount_of_feedback_scores_increased(
    opik_client: opik.Opik, dataset_name: str, experiment_name: str
):
    dataset = opik_client.create_dataset(dataset_name)

    dataset.insert(
        [
            {
                "input": {"question": "What is the of capital of France?"},
                "expected_model_output": {"output": "Paris"},
            },
        ]
    )

    def task(item: Dict[str, Any]):
        if item["input"] == {"question": "What is the of capital of France?"}:
            return {
                "output": "Paris",
                "reference": item["expected_model_output"]["output"],
            }

        raise AssertionError(
            f"Task received dataset item with an unexpected input: {item['input']}"
        )

    prompt = Prompt(
        name=f"test-experiment-prompt-{random_chars()}",
        prompt=f"test-experiment-prompt-template-{random_chars()}",
    )

    # Create the experiment first
    evaluation_result = opik.evaluate(
        dataset=dataset,
        task=task,
        scoring_metrics=[],
        experiment_name=experiment_name,
        experiment_config={
            "model_name": "gpt-3.5",
        },
        prompt=prompt,
    )
    opik.flush_tracker()

    verifiers.verify_experiment(
        opik_client=opik_client,
        id=evaluation_result.experiment_id,
        experiment_name=evaluation_result.experiment_name,
        experiment_metadata={
            "model_name": "gpt-3.5",
        },
        traces_amount=1,
        feedback_scores_amount=0,
        prompts=[prompt],
    )

    # Populate the existing experiment with a new feedback score
    evaluation_result = opik.evaluate_experiment(
        experiment_name=experiment_name,
        scoring_metrics=[
            metrics.Equals(name="metric1"),
            metrics.Equals(name="metric2"),
            metrics.Equals(name="metric3"),
        ],
    )
    opik.flush_tracker()

    verifiers.verify_experiment(
        opik_client=opik_client,
        id=evaluation_result.experiment_id,
        experiment_name=evaluation_result.experiment_name,
        experiment_metadata={
            "model_name": "gpt-3.5",
        },
        traces_amount=1,
        feedback_scores_amount=3,
        prompts=[prompt],
    )


def test_experiment__get_experiment_by_name__two_experiments_with_the_same_name(
    opik_client: opik.Opik, dataset_name: str, experiment_name: str
):
    dataset = opik_client.create_dataset(dataset_name)

    dataset.insert(
        [
            {
                "input": {"question": "What is the of capital of France?"},
                "expected_model_output": {"output": "Paris"},
            },
            {
                "input": {"question": "What is the of capital of Germany?"},
                "expected_model_output": {"output": "Berlin"},
            },
            {
                "input": {"question": "What is the of capital of Poland?"},
                "expected_model_output": {"output": "Warsaw"},
            },
        ]
    )

    def task(item: Dict[str, Any]):
        if item["input"] == {"question": "What is the of capital of France?"}:
            return {"output": "Paris"}
        if item["input"] == {"question": "What is the of capital of Germany?"}:
            return {"output": "Berlin"}
        if item["input"] == {"question": "What is the of capital of Poland?"}:
            return {"output": "Krakow"}

        raise AssertionError(
            f"Task received dataset item with an unexpected input: {item['input']}"
        )

    prompt = Prompt(
        name=f"test-experiment-prompt-{random_chars()}",
        prompt=f"test-experiment-prompt-template-{random_chars()}",
    )

    equals_metric = metrics.Equals()
    evaluation_result1 = opik.evaluate(
        dataset=dataset,
        task=task,
        scoring_metrics=[equals_metric],
        experiment_name=experiment_name,
        experiment_config={
            "model_name": "gpt-3.5",
        },
        scoring_key_mapping={
            "reference": lambda x: x["expected_model_output"]["output"],
        },
        prompt=prompt,
    )
    evaluation_result2 = opik.evaluate(
        dataset=dataset,
        task=task,
        scoring_metrics=[equals_metric],
        experiment_name=experiment_name,
        experiment_config={
            "model_name": "gpt-3.5",
        },
        scoring_key_mapping={
            "reference": lambda x: x["expected_model_output"]["output"],
        },
        prompt=prompt,
    )

    opik.flush_tracker()

    verifiers.verify_experiment(
        opik_client=opik_client,
        id=evaluation_result1.experiment_id,
        experiment_name=evaluation_result1.experiment_name,
        experiment_metadata={"model_name": "gpt-3.5"},
        traces_amount=3,  # one trace per dataset item
        feedback_scores_amount=1,
        prompts=[prompt],
    )
    verifiers.verify_experiment(
        opik_client=opik_client,
        id=evaluation_result2.experiment_id,
        experiment_name=evaluation_result2.experiment_name,
        experiment_metadata={"model_name": "gpt-3.5"},
        traces_amount=3,  # one trace per dataset item
        feedback_scores_amount=1,
        prompts=[prompt],
    )

    retrieved_experiment = opik_client.get_experiment_by_name(experiment_name)
    retrieved_experiments = opik_client.get_experiments_by_name(experiment_name)
    assert len(retrieved_experiments) == 2
    assert retrieved_experiment is not None
    assert retrieved_experiment.id in [e.id for e in retrieved_experiments]


def test_experiment__get_experiments_by_name(
    opik_client: opik.Opik, dataset_name: str, experiment_name: str
):
    dataset = opik_client.create_dataset(dataset_name)

    dataset.insert(
        [
            {
                "input": {"question": "What is the of capital of France?"},
                "expected_model_output": {"output": "Paris"},
            },
            {
                "input": {"question": "What is the of capital of Germany?"},
                "expected_model_output": {"output": "Berlin"},
            },
            {
                "input": {"question": "What is the of capital of Poland?"},
                "expected_model_output": {"output": "Warsaw"},
            },
        ]
    )

    def task(item: Dict[str, Any]):
        if item["input"] == {"question": "What is the of capital of France?"}:
            return {"output": "Paris"}
        if item["input"] == {"question": "What is the of capital of Germany?"}:
            return {"output": "Berlin"}
        if item["input"] == {"question": "What is the of capital of Poland?"}:
            return {"output": "Krakow"}

        raise AssertionError(
            f"Task received dataset item with an unexpected input: {item['input']}"
        )

    prompt = Prompt(
        name=f"test-experiment-prompt-{random_chars()}",
        prompt=f"test-experiment-prompt-template-{random_chars()}",
    )

    experiments_names = [experiment_name, experiment_name, random_chars(10)]

    evaluation_results = []
    equals_metric = metrics.Equals()
    for name in experiments_names:
        evaluation_result = opik.evaluate(
            dataset=dataset,
            task=task,
            scoring_metrics=[equals_metric],
            experiment_name=name,
            experiment_config={
                "model_name": "gpt-3.5",
            },
            scoring_key_mapping={
                "reference": lambda x: x["expected_model_output"]["output"],
            },
            prompt=prompt,
        )
        evaluation_results.append(evaluation_result)

    opik.flush_tracker()

    # make sure experiments saved and available
    for result in evaluation_results:
        verifiers.verify_experiment(
            opik_client=opik_client,
            id=result.experiment_id,
            experiment_name=result.experiment_name,
            experiment_metadata={"model_name": "gpt-3.5"},
            traces_amount=3,  # one trace per dataset item
            feedback_scores_amount=1,
            prompts=[prompt],
        )

    # check getting experiment by name
    experiments = opik_client.get_experiments_by_name(experiment_name)
    assert len(experiments) == 2

    experiments = opik_client.get_experiments_by_name(experiments_names[2])
    assert len(experiments) == 1


def test_experiment__get_experiment_by_id__experiment_not_found__ExperimentNotFound_error_is_raised(
    opik_client: opik.Opik,
):
    with pytest.raises(exceptions.ExperimentNotFound):
        opik_client.get_experiment_by_id("not-existing-id")


def test_experiment__get_experiment_by_name__experiment_not_found__ExperimentNotFound_error_is_raised(
    opik_client: opik.Opik,
):
    with pytest.raises(exceptions.ExperimentNotFound):
        opik_client.get_experiment_by_id("not-existing-name")


def test_experiment__get_experiment_items__no_feedback_scores(
    opik_client: opik.Opik, dataset_name: str, experiment_name: str
):
    dataset = opik_client.create_dataset(dataset_name)

    dataset.insert(
        [
            {
                "input": {"question": "What is the of capital of France?"},
                "expected_model_output": {"output": "Paris"},
            },
        ]
    )

    def task(item: Dict[str, Any]) -> Dict[str, Any]:
        return {
            "output": "Paris",
        }

    opik.evaluate(
        dataset=dataset,
        task=task,
        scoring_metrics=[],
        experiment_name=experiment_name,
    )

    opik.flush_tracker()

    experiment = opik_client.get_experiment_by_name(experiment_name)
    items = experiment.get_items()

    assert len(items) == 1
    assert items[0].feedback_scores == []
