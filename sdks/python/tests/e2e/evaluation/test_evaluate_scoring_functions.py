from typing import Dict, Any

import opik
from opik import synchronization
from opik.api_objects.experiment import experiment_item
from opik.evaluation import metrics
from opik.evaluation.metrics import score_result
from opik.message_processing.emulation import models
from opik.types import FeedbackScoreDict

from .. import verifiers
from ...testlib import assert_equal, ANY_BUT_NONE


def _wait_for_version(dataset, expected_version: str, timeout: float = 10) -> None:
    """Wait for dataset to have the expected version, fail if not reached."""
    success = synchronization.until(
        lambda: dataset.get_current_version_name() == expected_version,
        max_try_seconds=timeout,
    )
    assert success, f"Expected version '{expected_version}' was not created in time"


DATASET_ITEMS = [
    {
        "input": {"question": "What is the of capital of Ukraine?"},
        "expected_model_output": {"output": "Kyiv"},
    },
]


def llm_task(item: Dict[str, Any]):
    if item["input"] == {"question": "What is the of capital of Ukraine?"}:
        return {"output": "Kyiv"}

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


def task_span_name_scoring_function(
    task_span: models.SpanModel, **ignored_kwargs: Any
) -> score_result.ScoreResult:
    score = 1.0 if task_span.name == "llm_task" else 0.0
    return score_result.ScoreResult(
        value=score,
        name="task_span_name_scoring_function",
        reason="Correct task span name" if score == 1.0 else "Incorrect task span name",
    )


def task_span_name_and_equals_scoring_function(
    dataset_item: Dict[str, Any],
    task_outputs: Dict[str, Any],
    task_span: models.SpanModel,
) -> score_result.ScoreResult:
    score = 1.0 if task_span.name == "llm_task" else 0.0

    reference = dataset_item["expected_model_output"]["output"]
    prediction = task_outputs["output"]
    if reference == prediction:
        value = 1.0
    else:
        value = 0.0

    score *= value

    return score_result.ScoreResult(
        value=score,
        name="task_span_name_and_equals_scoring_function",
        reason="Correct task span name and output value"
        if score == 1.0
        else "Incorrect task span name and output value",
    )


def test_evaluate__scoring_functions__happy_flow(
    opik_client: opik.Opik, dataset_name: str, experiment_name: str
):
    # Tests that ordinary scoring functions work correctly.
    dataset = opik_client.create_dataset(dataset_name)
    dataset.insert(DATASET_ITEMS)
    _wait_for_version(dataset, "v1")

    # Get the version ID to verify it's passed to the experiment
    version_info = dataset.get_version_info()

    evaluation_result = opik.evaluate(
        dataset=dataset,
        task=llm_task,
        scoring_functions=[equals_scoring_function],
        experiment_name=experiment_name,
        experiment_config={
            "model_name": "gpt-3.5",
        },
    )

    opik.flush_tracker()

    verifiers.verify_experiment(
        opik_client=opik_client,
        id=evaluation_result.experiment_id,
        experiment_name=evaluation_result.experiment_name,
        experiment_metadata={"model_name": "gpt-3.5"},
        traces_amount=1,  # one trace per dataset item
        feedback_scores_amount=1,
        prompts=None,
        dataset_version_id=version_info.id,
    )

    assert evaluation_result.dataset_id == dataset.id

    retrieved_experiment = opik_client.get_experiment_by_id(
        evaluation_result.experiment_id
    )
    experiment_items_contents = retrieved_experiment.get_items()
    assert len(experiment_items_contents) == 1

    EXPECTED_EXPERIMENT_ITEMS_CONTENT = [
        experiment_item.ExperimentItemContent(
            id=ANY_BUT_NONE,
            dataset_item_id=ANY_BUT_NONE,
            trace_id=ANY_BUT_NONE,
            dataset_item_data={
                "input": {"question": "What is the of capital of Ukraine?"},
                "expected_model_output": {"output": "Kyiv"},
                "id": ANY_BUT_NONE,
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
        )
    ]
    assert_equal(
        expected=EXPECTED_EXPERIMENT_ITEMS_CONTENT,
        actual=experiment_items_contents,
    )


def test_evaluate__scoring_functions_mixed_with_scoring_metrics__happy_flow(
    opik_client: opik.Opik, dataset_name: str, experiment_name: str
):
    # Tests that mix of ordinary scoring functions and scoring metrics work correctly.
    dataset = opik_client.create_dataset(dataset_name)
    dataset.insert(DATASET_ITEMS)

    equals_metric = metrics.Equals()
    evaluation_result = opik.evaluate(
        dataset=dataset,
        task=llm_task,
        scoring_metrics=[equals_metric],
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

    verifiers.verify_experiment(
        opik_client=opik_client,
        id=evaluation_result.experiment_id,
        experiment_name=evaluation_result.experiment_name,
        experiment_metadata={"model_name": "gpt-3.5"},
        traces_amount=1,  # one trace per dataset item
        feedback_scores_amount=2,
        prompts=None,
    )

    assert evaluation_result.dataset_id == dataset.id

    retrieved_experiment = opik_client.get_experiment_by_id(
        evaluation_result.experiment_id
    )
    experiment_items_contents = retrieved_experiment.get_items()
    assert len(experiment_items_contents) == 1

    EXPECTED_EXPERIMENT_ITEMS_CONTENT = [
        experiment_item.ExperimentItemContent(
            id=ANY_BUT_NONE,
            dataset_item_id=ANY_BUT_NONE,
            trace_id=ANY_BUT_NONE,
            dataset_item_data={
                "input": {"question": "What is the of capital of Ukraine?"},
                "expected_model_output": {"output": "Kyiv"},
                "id": ANY_BUT_NONE,
            },
            evaluation_task_output={"output": "Kyiv"},
            feedback_scores=[
                FeedbackScoreDict(
                    category_name=None, name="equals_metric", reason=None, value=1.0
                ),
                FeedbackScoreDict(
                    category_name=None,
                    name="equals_scoring_function",
                    reason="Correct output value",
                    value=1.0,
                ),
            ],
        )
    ]

    # sort feedback scores by name
    for item in experiment_items_contents:
        item.feedback_scores = sorted(item.feedback_scores, key=lambda x: x["name"])

    assert_equal(
        expected=EXPECTED_EXPERIMENT_ITEMS_CONTENT, actual=experiment_items_contents
    )


def test_evaluate__scoring_functions_mixed_with_task_span_scoring_functions__happy_flow(
    opik_client: opik.Opik, dataset_name: str, experiment_name: str
):
    # Tests that mix of ordinary scoring functions and task span scoring functions work correctly.
    # Also, it checks that task span scoring functions can access:
    # task span, dataset item content (dataset_item), and task output (task_outputs) parameters.
    dataset = opik_client.create_dataset(dataset_name)
    dataset.insert(DATASET_ITEMS)

    evaluation_result = opik.evaluate(
        dataset=dataset,
        task=llm_task,
        scoring_functions=[
            equals_scoring_function,
            task_span_name_scoring_function,
            task_span_name_and_equals_scoring_function,
        ],
        experiment_name=experiment_name,
        experiment_config={
            "model_name": "gpt-3.5",
        },
    )

    opik.flush_tracker()

    verifiers.verify_experiment(
        opik_client=opik_client,
        id=evaluation_result.experiment_id,
        experiment_name=evaluation_result.experiment_name,
        experiment_metadata={"model_name": "gpt-3.5"},
        traces_amount=1,  # one trace per dataset item
        feedback_scores_amount=3,
        prompts=None,
    )

    assert evaluation_result.dataset_id == dataset.id

    retrieved_experiment = opik_client.get_experiment_by_id(
        evaluation_result.experiment_id
    )
    experiment_items_contents = retrieved_experiment.get_items()
    assert len(experiment_items_contents) == 1

    EXPECTED_EXPERIMENT_ITEMS_CONTENT = [
        experiment_item.ExperimentItemContent(
            id=ANY_BUT_NONE,
            dataset_item_id=ANY_BUT_NONE,
            trace_id=ANY_BUT_NONE,
            dataset_item_data={
                "input": {"question": "What is the of capital of Ukraine?"},
                "expected_model_output": {"output": "Kyiv"},
                "id": ANY_BUT_NONE,
            },
            evaluation_task_output={"output": "Kyiv"},
            feedback_scores=[
                FeedbackScoreDict(
                    category_name=None,
                    name="equals_scoring_function",
                    reason="Correct output value",
                    value=1.0,
                ),
                FeedbackScoreDict(
                    category_name=None,
                    name="task_span_name_and_equals_scoring_function",
                    reason="Correct task span name and output value",
                    value=1.0,
                ),
                FeedbackScoreDict(
                    category_name=None,
                    name="task_span_name_scoring_function",
                    reason="Correct task span name",
                    value=1.0,
                ),
            ],
        ),
    ]
    # sort feedback scores by name
    for item in experiment_items_contents:
        item.feedback_scores = sorted(item.feedback_scores, key=lambda x: x["name"])

    assert_equal(
        expected=EXPECTED_EXPERIMENT_ITEMS_CONTENT, actual=experiment_items_contents
    )
