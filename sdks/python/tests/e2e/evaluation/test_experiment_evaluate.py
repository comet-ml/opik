from typing import Dict, Any, List

import opik

from opik import Prompt, synchronization, exceptions, id_helpers
from opik.api_objects.dataset import dataset_item
from opik.evaluation import metrics
from opik.evaluation import test_result
from opik.evaluation.metrics import score_result
from opik.api_objects.experiment import experiment_item
from .. import verifiers
from ..conftest import random_chars
from ...testlib import assert_equal, ANY_BUT_NONE

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

    experiment_tags = ["capital", "geography", "europe"]

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
        experiment_tags=experiment_tags,
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
        experiment_tags=experiment_tags,
    )

    assert (
        evaluation_result.dataset_id == dataset.id
    ), f"Expected evaluation result dataset_id '{dataset.id}', but got '{evaluation_result.dataset_id}'"

    retrieved_experiment = opik_client.get_experiment_by_name(experiment_name)
    experiment_items_contents = retrieved_experiment.get_items()
    assert len(experiment_items_contents) == 3, (
        f"Expected 3 experiment items, but got {len(experiment_items_contents)}. "
        f"Experiment items: {experiment_items_contents}"
    )

    EXPECTED_EXPERIMENT_ITEMS_CONTENT = [
        experiment_item.ExperimentItemContent(
            id=ANY_BUT_NONE,
            dataset_item_id=ANY_BUT_NONE,
            trace_id=ANY_BUT_NONE,
            dataset_item_data={
                "input": {"question": "What is the of capital of France?"},
                "expected_model_output": {"output": "Paris"},
                "id": ANY_BUT_NONE,
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
                "id": ANY_BUT_NONE,
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
                "id": ANY_BUT_NONE,
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

    assert (
        evaluation_result.dataset_id == dataset.id
    ), f"Expected evaluation result dataset_id '{dataset.id}', but got '{evaluation_result.dataset_id}'"

    retrieved_experiment = opik_client.get_experiment_by_name(experiment_name)
    experiment_items_contents = retrieved_experiment.get_items()
    assert len(experiment_items_contents) == 2, (
        f"Expected 2 experiment items, but got {len(experiment_items_contents)}. "
        f"Experiment items: {experiment_items_contents}"
    )

    EXPECTED_EXPERIMENT_ITEMS_CONTENT = [
        experiment_item.ExperimentItemContent(
            id=ANY_BUT_NONE,
            dataset_item_id=ANY_BUT_NONE,
            trace_id=ANY_BUT_NONE,
            dataset_item_data={
                "input": {"question": "What is the of capital of France?"},
                "expected_model_output": {"output": "Paris"},
                "id": ANY_BUT_NONE,
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
                "id": ANY_BUT_NONE,
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

    assert (
        evaluation_result.dataset_id == dataset.id
    ), f"Expected evaluation result dataset_id '{dataset.id}', but got '{evaluation_result.dataset_id}'"

    retrieved_experiment = opik_client.get_experiment_by_name(experiment_name)
    experiment_items_contents = retrieved_experiment.get_items()
    assert len(experiment_items_contents) == 3, (
        f"Expected 3 experiment items, but got {len(experiment_items_contents)}. "
        f"Experiment items: {experiment_items_contents}"
    )

    EXPECTED_EXPERIMENT_ITEMS_CONTENT = [
        experiment_item.ExperimentItemContent(
            id=ANY_BUT_NONE,
            dataset_item_id=ANY_BUT_NONE,
            trace_id=ANY_BUT_NONE,
            dataset_item_data={
                "input": {"question": "What is the of capital of France?"},
                "expected_model_output": {"output": "Paris"},
                "id": ANY_BUT_NONE,
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
                "id": ANY_BUT_NONE,
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
                "id": ANY_BUT_NONE,
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

    assert experiment_content.name is not None, (
        f"Expected experiment name to be set by backend, but got None. "
        f"Experiment ID: {experiment_id}, Experiment content: {experiment_content}"
    )


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

    assert (
        evaluation_result.dataset_id == dataset.id
    ), f"Expected evaluation result dataset_id '{dataset.id}', but got '{evaluation_result.dataset_id}'"


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
    assert len(retrieved_experiments) == 2, (
        f"Expected 2 experiments with name '{experiment_name}', but got {len(retrieved_experiments)}. "
        f"Retrieved experiments: {[e.id for e in retrieved_experiments]}"
    )
    assert retrieved_experiment is not None, (
        f"Expected get_experiment_by_name to return an experiment, but got None. "
        f"Experiment name: {experiment_name}"
    )
    assert retrieved_experiment.id in [e.id for e in retrieved_experiments], (
        f"Expected retrieved experiment ID '{retrieved_experiment.id}' to be in the list of experiments. "
        f"Experiment IDs: {[e.id for e in retrieved_experiments]}"
    )


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
    assert len(experiments) == 2, (
        f"Expected 2 experiments with name '{experiment_name}', but got {len(experiments)}. "
        f"Experiment IDs: {[e.id for e in experiments]}"
    )

    experiments = opik_client.get_experiments_by_name(experiments_names[2])
    assert len(experiments) == 1, (
        f"Expected 1 experiment with name '{experiments_names[2]}', but got {len(experiments)}. "
        f"Experiment IDs: {[e.id for e in experiments]}"
    )


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

    assert len(items) == 1, (
        f"Expected 1 experiment item, but got {len(items)}. " f"Items: {items}"
    )
    assert items[0].feedback_scores == [], (
        f"Expected empty feedback scores, but got {items[0].feedback_scores}. "
        f"Item: {items[0]}"
    )


def test_experiment_creation_via_evaluate_function__with_experiment_scoring_functions__scores_computed_and_logged(
    opik_client: opik.Opik, dataset_name: str, experiment_name: str
):
    """Test that experiment scoring functions compute and log experiment-level scores."""
    dataset = opik_client.create_dataset(dataset_name)

    dataset.insert(
        [
            {
                "input": {"question": "What is the capital of France?"},
                "expected_model_output": {"output": "Paris"},
            },
            {
                "input": {"question": "What is the capital of Germany?"},
                "expected_model_output": {"output": "Berlin"},
            },
            {
                "input": {"question": "What is the capital of Poland?"},
                "expected_model_output": {"output": "Warsaw"},
            },
        ]
    )

    def task(item: Dict[str, Any]):
        if item["input"] == {"question": "What is the capital of France?"}:
            return {"output": "Paris"}
        if item["input"] == {"question": "What is the capital of Germany?"}:
            return {"output": "Berlin"}
        if item["input"] == {"question": "What is the capital of Poland?"}:
            return {"output": "Krakow"}  # Wrong answer

        raise AssertionError(
            f"Task received dataset item with an unexpected input: {item['input']}"
        )

    def constant_score(
        test_results: List[test_result.TestResult],
    ) -> List[score_result.ScoreResult]:
        """Compute a random number based on the number of test results."""
        return [
            score_result.ScoreResult(
                name="fixed_number",
                value=0.8,
                reason="Fixed score of 0.8",
            )
        ]

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
        experiment_scoring_functions=[constant_score],
    )

    opik.flush_tracker()

    # Verify experiment scores are in the result
    assert len(evaluation_result.experiment_scores) == 1, (
        f"Expected 1 experiment score in evaluation result, but got {len(evaluation_result.experiment_scores)}. "
        f"Experiment scores: {evaluation_result.experiment_scores}"
    )
    assert evaluation_result.experiment_scores[0].name == "fixed_number", (
        f"Expected experiment score name 'fixed_number', but got '{evaluation_result.experiment_scores[0].name}'. "
        f"Full score object: {evaluation_result.experiment_scores[0]}"
    )
    assert evaluation_result.experiment_scores[0].value == 0.8, (
        f"Expected experiment score value 0.8, but got {evaluation_result.experiment_scores[0].value}. "
        f"Full score object: {evaluation_result.experiment_scores[0]}"
    )
    assert evaluation_result.experiment_scores[0].reason is not None, (
        f"Expected experiment score reason to be set, but got None. "
        f"Score: {evaluation_result.experiment_scores[0]}"
    )
    assert (
        "Fixed score of 0.8" in evaluation_result.experiment_scores[0].reason
    ), f"Expected reason to contain 'Fixed score of 0.8', but got: '{evaluation_result.experiment_scores[0].reason}'"

    # Verify experiment was created with experiment scores
    verifiers.verify_experiment(
        opik_client=opik_client,
        id=evaluation_result.experiment_id,
        experiment_name=evaluation_result.experiment_name,
        experiment_metadata={"model_name": "gpt-3.5"},
        traces_amount=3,  # one trace per dataset item
        feedback_scores_amount=1,
        experiment_scores={"fixed_number": 0.8},
    )
