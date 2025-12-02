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


def test__experiment_scores__happy_path(
    opik_client: opik.Opik, dataset_name: str, experiment_name: str
):
    """Test that experiment scoring functions are executed and scores are logged."""

    def compute_experiment_scores(test_results):
        """Aggregate scores across all test results."""
        # Extract all scoring function values
        all_scores = []
        for result in test_results:
            if result.score_results:
                all_scores.extend([score.value for score in result.score_results])

        if not all_scores:
            return []

        # Compute aggregate metrics
        return [
            score_result.ScoreResult(
                name="max_score",
                value=max(all_scores),
                reason=f"Maximum score across {len(all_scores)} measurements",
            ),
            score_result.ScoreResult(
                name="min_score",
                value=min(all_scores),
                reason=f"Minimum score across {len(all_scores)} measurements",
            ),
            score_result.ScoreResult(
                name="avg_score",
                value=sum(all_scores) / len(all_scores),
                reason=f"Average score across {len(all_scores)} measurements",
            ),
        ]

    # Create dataset
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

    # Run evaluation with experiment scoring functions
    evaluation_result = opik.evaluate(
        dataset=dataset,
        task=llm_task,
        scoring_functions=[equals_scoring_function],
        experiment_scoring_functions=[compute_experiment_scores],
        experiment_name=experiment_name,
        experiment_config={
            "model_name": "test-model",
        },
        scoring_key_mapping={
            "reference": lambda x: x["expected_model_output"]["output"],
        },
    )

    opik.flush_tracker()

    # Verify experiment was created with experiment scores
    verifiers.verify_experiment(
        opik_client=opik_client,
        id=evaluation_result.experiment_id,
        experiment_name=evaluation_result.experiment_name,
        experiment_metadata={"model_name": "test-model"},
        traces_amount=3,
        feedback_scores_amount=1,
    )

    # Verify experiment scores are present in evaluation result
    assert (
        evaluation_result.experiment_scores is not None
    ), "Experiment scores should not be None"
    assert (
        len(evaluation_result.experiment_scores) == 3
    ), f"Expected 3 experiment scores, got {len(evaluation_result.experiment_scores)}"

    score_names = {score.name for score in evaluation_result.experiment_scores}
    assert score_names == {
        "max_score",
        "min_score",
        "avg_score",
    }, f"Expected score names {{max_score, min_score, avg_score}}, got {score_names}"

    # Verify experiment scores are retrievable via SDK API
    retrieved_experiment = opik_client.get_experiment_by_name(experiment_name)
    rest_client = opik_client._rest_client
    experiment_content = rest_client.experiments.get_experiment_by_id(
        retrieved_experiment.id
    )

    assert (
        experiment_content.experiment_scores is not None
    ), "Experiment scores should be persisted in backend"
    assert (
        len(experiment_content.experiment_scores) == 3
    ), f"Expected 3 experiment scores in backend, got {len(experiment_content.experiment_scores)}"

    backend_score_names = {score.name for score in experiment_content.experiment_scores}
    assert (
        backend_score_names == {"max_score", "min_score", "avg_score"}
    ), f"Expected backend score names {{max_score, min_score, avg_score}}, got {backend_score_names}"

    # Verify score values are reasonable
    max_score = next(
        s for s in evaluation_result.experiment_scores if s.name == "max_score"
    )
    min_score = next(
        s for s in evaluation_result.experiment_scores if s.name == "min_score"
    )
    avg_score = next(
        s for s in evaluation_result.experiment_scores if s.name == "avg_score"
    )

    assert (
        0.0 <= max_score.value <= 1.0
    ), f"max_score should be in [0,1], got {max_score.value}"
    assert (
        0.0 <= min_score.value <= 1.0
    ), f"min_score should be in [0,1], got {min_score.value}"
    assert (
        0.0 <= avg_score.value <= 1.0
    ), f"avg_score should be in [0,1], got {avg_score.value}"
    assert (
        min_score.value <= avg_score.value <= max_score.value
    ), f"Score ordering should be min <= avg <= max, got {min_score.value} <= {avg_score.value} <= {max_score.value}"
