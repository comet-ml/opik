from typing import Dict, Any

import opik
from opik.api_objects.experiment import experiment_item
from opik.evaluation.metrics import score_result
from opik.types import FeedbackScoreDict

from . import verifiers
from ..testlib import assert_equal, ANY_BUT_NONE


def llm_task(item: Dict[str, Any]):
    if item["input"] == {"question": "What is the capital of Ukraine?"}:
        return {"output": "Kyiv"}
    if item["input"] == {"question": "What is the capital of France?"}:
        return {"output": "Paris"}
    if item["input"] == {"question": "What is the capital of Germany?"}:
        return {"output": "Berlin"}
    if item["input"] == {"question": "What is the capital of Poland?"}:
        return {"output": "Krakow"}

    raise AssertionError(
        f"Task received dataset item with an unexpected input: {item['input']}"
    )


def equals_scoring_function(dataset_item: Dict[str, Any], task_outputs: Dict[str, Any]):
    reference = dataset_item["expected_model_output"]["output"]
    prediction = task_outputs["output"]
    if reference == prediction:
        value = 1.0
    else:
        value = 0.0
    return score_result.ScoreResult(
        name="equals_scoring_function",
        value=value,
        reason="Correct output value" if value == 1.0 else "Incorrect output value",
    )


def test__find_experiment_items_for_dataset__happy_path(
    opik_client: opik.Opik, dataset_name: str, experiment_name: str
):
    dataset = opik_client.create_dataset(dataset_name)

    dataset.insert(
        [
            {
                "input": {"question": "What is the capital of Germany?"},
                "expected_model_output": {"output": "Berlin"},
            },
            {
                "input": {"question": "What is the capital of Poland?"},
                "expected_model_output": {"output": "Warsaw"},
            },
            {
                "input": {"question": "What is the capital of Ukraine?"},
                "expected_model_output": {"output": "Kyiv"},
            },
        ]
    )

    evaluation_result = opik.evaluate(
        dataset=dataset,
        task=llm_task,
        scoring_functions=[equals_scoring_function],
        experiment_name=experiment_name,
        experiment_config={
            "model_name": "gpt-3.5",
        },
        scoring_key_mapping={
            "reference": lambda x: x["expected_model_output"]["output"],
        },
    )

    opik.flush_tracker()

    # make sure experiments saved and available
    verifiers.verify_experiment(
        opik_client=opik_client,
        id=evaluation_result.experiment_id,
        experiment_name=evaluation_result.experiment_name,
        experiment_metadata={"model_name": "gpt-3.5"},
        traces_amount=3,  # one trace per dataset item
        feedback_scores_amount=1,
    )

    # find experiment items for dataset
    retrieved_experiment = opik_client.get_experiment_by_name(experiment_name)
    experiments = opik_client.get_experiments_client()
    experiment_items_contents = experiments.find_experiment_items_for_dataset(
        dataset_name=dataset_name,
        experiment_ids=[retrieved_experiment.id],
    )

    assert len(experiment_items_contents) == 3

    EXPECTED_EXPERIMENT_ITEMS_CONTENT = [
        experiment_item.ExperimentItemContent(
            id=ANY_BUT_NONE,
            dataset_item_id=ANY_BUT_NONE,
            trace_id=ANY_BUT_NONE,
            dataset_item_data={
                "expected_model_output": {"output": "Kyiv"},
                "id": ANY_BUT_NONE,
                "input": {"question": "What is the capital of Ukraine?"},
            },
            evaluation_task_output={"output": "Kyiv"},
            feedback_scores=[
                FeedbackScoreDict(
                    category_name=None,
                    name="equals_scoring_function",
                    reason="Correct output value",
                    value=1.0,
                )
            ],
        ),
        experiment_item.ExperimentItemContent(
            id=ANY_BUT_NONE,
            dataset_item_id=ANY_BUT_NONE,
            trace_id=ANY_BUT_NONE,
            dataset_item_data={
                "expected_model_output": {"output": "Warsaw"},
                "id": ANY_BUT_NONE,
                "input": {"question": "What is the capital of Poland?"},
            },
            evaluation_task_output={"output": "Krakow"},
            feedback_scores=[
                FeedbackScoreDict(
                    category_name=None,
                    name="equals_scoring_function",
                    reason="Incorrect output value",
                    value=0.0,
                )
            ],
        ),
        experiment_item.ExperimentItemContent(
            id=ANY_BUT_NONE,
            dataset_item_id=ANY_BUT_NONE,
            trace_id=ANY_BUT_NONE,
            dataset_item_data={
                "expected_model_output": {"output": "Berlin"},
                "id": ANY_BUT_NONE,
                "input": {"question": "What is the capital of Germany?"},
            },
            evaluation_task_output={"output": "Berlin"},
            feedback_scores=[
                FeedbackScoreDict(
                    category_name=None,
                    name="equals_scoring_function",
                    reason="Correct output value",
                    value=1.0,
                )
            ],
        ),
    ]

    assert_equal(
        expected=sorted(
            EXPECTED_EXPERIMENT_ITEMS_CONTENT,
            key=lambda item: str(item.evaluation_task_output),
        ),
        actual=sorted(
            experiment_items_contents, key=lambda item: str(item.evaluation_task_output)
        ),
    )


def test__find_experiment_items_for_dataset__filtered__happy_path(
    opik_client: opik.Opik, dataset_name: str, experiment_name: str
):
    dataset = opik_client.create_dataset(dataset_name)

    dataset.insert(
        [
            {
                "input": {"question": "What is the capital of Germany?"},
                "expected_model_output": {"output": "Berlin"},
            },
            {
                "input": {"question": "What is the capital of Poland?"},
                "expected_model_output": {"output": "Warsaw"},
            },
            {
                "input": {"question": "What is the capital of Ukraine?"},
                "expected_model_output": {"output": "Kyiv"},
            },
        ]
    )

    evaluation_result = opik.evaluate(
        dataset=dataset,
        task=llm_task,
        scoring_functions=[equals_scoring_function],
        experiment_name=experiment_name,
        experiment_config={
            "model_name": "gpt-3.5",
        },
        scoring_key_mapping={
            "reference": lambda x: x["expected_model_output"]["output"],
        },
    )

    opik.flush_tracker()

    # make sure experiments saved and available
    verifiers.verify_experiment(
        opik_client=opik_client,
        id=evaluation_result.experiment_id,
        experiment_name=evaluation_result.experiment_name,
        experiment_metadata={"model_name": "gpt-3.5"},
        traces_amount=3,  # one trace per dataset item
        feedback_scores_amount=1,
    )

    # find experiment items for dataset
    retrieved_experiment = opik_client.get_experiment_by_name(experiment_name)
    experiments = opik_client.get_experiments_client()
    experiment_items_contents = experiments.find_experiment_items_for_dataset(
        dataset_name=dataset_name,
        experiment_ids=[retrieved_experiment.id],
        filter_string="feedback_scores.equals_scoring_function = 0.0",
    )

    assert len(experiment_items_contents) == 1

    EXPECTED_EXPERIMENT_ITEMS_CONTENT = [
        experiment_item.ExperimentItemContent(
            id=ANY_BUT_NONE,
            dataset_item_id=ANY_BUT_NONE,
            trace_id=ANY_BUT_NONE,
            dataset_item_data={
                "expected_model_output": {"output": "Warsaw"},
                "id": ANY_BUT_NONE,
                "input": {"question": "What is the capital of Poland?"},
            },
            evaluation_task_output={"output": "Krakow"},
            feedback_scores=[
                FeedbackScoreDict(
                    category_name=None,
                    name="equals_scoring_function",
                    reason="Incorrect output value",
                    value=0.0,
                )
            ],
        )
    ]

    assert_equal(
        expected=EXPECTED_EXPERIMENT_ITEMS_CONTENT,
        actual=experiment_items_contents,
    )
