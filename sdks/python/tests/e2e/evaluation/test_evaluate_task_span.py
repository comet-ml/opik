from typing import Dict, Any, Optional

import opik
from opik import Prompt
from opik.evaluation import metrics
from opik.evaluation.metrics import BaseMetric, score_result
from opik.message_processing.emulation import models

from .. import verifiers
from ..conftest import random_chars


class TaskSpanTestMetric(BaseMetric):
    def __init__(
        self,
        name: str = "task_span_test_metric",
        track: bool = True,
        project_name: Optional[str] = None,
    ):
        super().__init__(name=name, track=track, project_name=project_name)

    def score(self, task_span: models.SpanModel) -> score_result.ScoreResult:
        score = 1.0 if task_span.name == "task" else 0.0
        return score_result.ScoreResult(
            value=score,
            name=self.name,
            reason="Correct task span name"
            if score == 1.0
            else "Incorrect task span name",
        )


class TaskSpanInputTestMetric(BaseMetric):
    def __init__(
        self,
        name: str = "task_span_input_test_metric",
        track: bool = True,
        project_name: Optional[str] = None,
    ):
        super().__init__(name=name, track=track, project_name=project_name)

    def score(self, task_span: models.SpanModel) -> score_result.ScoreResult:
        input_data = task_span.input
        has_question = (
            isinstance(input_data, dict)
            and "item" in input_data
            and "input" in input_data["item"]
            and isinstance(input_data["item"]["input"], dict)
            and "question" in input_data["item"]["input"]
        )
        score = 1.0 if has_question else 0.0
        return score_result.ScoreResult(
            value=score,
            name=self.name,
            reason="Task span has question input"
            if score == 1.0
            else "Task span missing question input",
        )


def test_evaluate__with_task_span_metrics__single_metric__happy_flow(
    opik_client: opik.Opik, dataset_name: str, experiment_name: str
):
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
        ]
    )

    def task(item: Dict[str, Any]):
        if item["input"] == {"question": "What is the capital of France?"}:
            return {"output": "Paris"}
        if item["input"] == {"question": "What is the capital of Germany?"}:
            return {"output": "Berlin"}

        raise AssertionError(
            f"Task received dataset item with an unexpected input: {item['input']}"
        )

    prompt = Prompt(
        name=f"test-task-span-prompt-{random_chars()}",
        prompt=f"test-task-span-prompt-template-{random_chars()}",
    )

    task_span_metric = TaskSpanTestMetric()
    equals_metric = metrics.Equals()

    evaluation_result = opik.evaluate(
        dataset=dataset,
        task=task,
        scoring_metrics=[equals_metric, task_span_metric],
        experiment_name=experiment_name,
        experiment_config={
            "model_name": "test-model",
        },
        scoring_key_mapping={
            "reference": lambda x: x["expected_model_output"]["output"],
        },
        prompts=[prompt],
    )

    opik.flush_tracker()

    verifiers.verify_experiment(
        opik_client=opik_client,
        id=evaluation_result.experiment_id,
        experiment_name=evaluation_result.experiment_name,
        experiment_metadata={"model_name": "test-model"},
        traces_amount=2,
        feedback_scores_amount=2,  # equals_metric + task_span_metric
        prompts=[prompt],
    )

    assert evaluation_result.dataset_id == dataset.id

    retrieved_experiment = opik_client.get_experiment_by_name(experiment_name)
    experiment_items_contents = retrieved_experiment.get_items()
    assert len(experiment_items_contents) == 2

    for item in experiment_items_contents:
        assert len(item.feedback_scores) == 2
        score_names = [score["name"] for score in item.feedback_scores]
        assert "equals_metric" in score_names
        assert "task_span_test_metric" in score_names

        # Find task span metric score
        task_span_score = next(
            score
            for score in item.feedback_scores
            if score["name"] == "task_span_test_metric"
        )
        assert task_span_score["value"] == 1.0
        assert "Correct task span name" in task_span_score["reason"]


def test_evaluate__with_task_span_metrics__multiple_task_span_metrics__happyflow(
    opik_client: opik.Opik, dataset_name: str, experiment_name: str
):
    dataset = opik_client.create_dataset(dataset_name)

    dataset.insert(
        [
            {
                "input": {"question": "What is the capital of Spain?"},
                "expected_model_output": {"output": "Madrid"},
            },
        ]
    )

    def task(item: Dict[str, Any]):
        if item["input"] == {"question": "What is the capital of Spain?"}:
            return {"output": "Madrid"}

        raise AssertionError(
            f"Task received dataset item with an unexpected input: {item['input']}"
        )

    task_span_metric_1 = TaskSpanTestMetric(name="task_span_metric_1")
    task_span_metric_2 = TaskSpanInputTestMetric(name="task_span_metric_2")
    equals_metric = metrics.Equals()

    evaluation_result = opik.evaluate(
        dataset=dataset,
        task=task,
        scoring_metrics=[equals_metric, task_span_metric_1, task_span_metric_2],
        experiment_name=experiment_name,
        experiment_config={
            "model_name": "test-model-v2",
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
        experiment_metadata={"model_name": "test-model-v2"},
        traces_amount=1,
        feedback_scores_amount=3,  # equals_metric + 2 task_span_metrics
    )

    assert evaluation_result.dataset_id == dataset.id

    retrieved_experiment = opik_client.get_experiment_by_name(experiment_name)
    experiment_items_contents = retrieved_experiment.get_items()
    assert len(experiment_items_contents) == 1

    item = experiment_items_contents[0]
    assert len(item.feedback_scores) == 3
    score_names = [score["name"] for score in item.feedback_scores]
    assert "equals_metric" in score_names
    assert "task_span_metric_1" in score_names
    assert "task_span_metric_2" in score_names

    # Verify all task span metrics scored correctly
    for score in item.feedback_scores:
        if score["name"] in ["task_span_metric_1", "task_span_metric_2"]:
            assert score["value"] == 1.0


def test_evaluate__with_task_span_metrics__only_task_span_metrics__no_regular_metrics(
    opik_client: opik.Opik, dataset_name: str, experiment_name: str
):
    dataset = opik_client.create_dataset(dataset_name)

    dataset.insert(
        [
            {
                "input": {"question": "What is the capital of Italy?"},
                "expected_model_output": {"output": "Rome"},
            },
        ]
    )

    def task(item: Dict[str, Any]):
        if item["input"] == {"question": "What is the capital of Italy?"}:
            return {"output": "Rome"}

        raise AssertionError(
            f"Task received dataset item with an unexpected input: {item['input']}"
        )

    task_span_metric = TaskSpanTestMetric()

    evaluation_result = opik.evaluate(
        dataset=dataset,
        task=task,
        scoring_metrics=[task_span_metric],
        experiment_name=experiment_name,
        experiment_config={
            "model_name": "task-span-only-model",
        },
    )

    opik.flush_tracker()

    verifiers.verify_experiment(
        opik_client=opik_client,
        id=evaluation_result.experiment_id,
        experiment_name=evaluation_result.experiment_name,
        experiment_metadata={"model_name": "task-span-only-model"},
        traces_amount=1,
        feedback_scores_amount=1,  # only task_span_metric
    )

    assert evaluation_result.dataset_id == dataset.id

    retrieved_experiment = opik_client.get_experiment_by_name(experiment_name)
    experiment_items_contents = retrieved_experiment.get_items()
    assert len(experiment_items_contents) == 1

    item = experiment_items_contents[0]
    assert len(item.feedback_scores) == 1
    score = item.feedback_scores[0]
    assert score["name"] == "task_span_test_metric"
    assert score["value"] == 1.0


def test_evaluate__with_task_span_metrics__mixed_with_regular_metrics__comprehensive_evaluation(
    opik_client: opik.Opik, dataset_name: str, experiment_name: str
):
    dataset = opik_client.create_dataset(dataset_name)

    dataset.insert(
        [
            {
                "input": {"question": "What is the capital of Japan?"},
                "expected_model_output": {"output": "Tokyo"},
            },
            {
                "input": {"question": "What is the capital of Canada?"},
                "expected_model_output": {"output": "Ottawa"},
            },
        ]
    )

    def task(item: Dict[str, Any]):
        if item["input"] == {"question": "What is the capital of Japan?"}:
            return {"output": "Tokyo"}
        if item["input"] == {"question": "What is the capital of Canada?"}:
            return {"output": "Ottawa"}

        raise AssertionError(
            f"Task received dataset item with an unexpected input: {item['input']}"
        )

    prompt = Prompt(
        name=f"test-mixed-metrics-prompt-{random_chars()}",
        prompt=f"test-mixed-metrics-prompt-template-{random_chars()}",
    )

    # Mix of regular and task span metrics
    equals_metric = metrics.Equals(name="regular_equals")
    contains_metric = metrics.Contains(name="regular_contains")
    task_span_metric = TaskSpanTestMetric(name="span_name_check")
    task_span_input_metric = TaskSpanInputTestMetric(name="span_input_check")

    evaluation_result = opik.evaluate(
        dataset=dataset,
        task=task,
        scoring_metrics=[
            equals_metric,
            task_span_metric,
            contains_metric,
            task_span_input_metric,
        ],
        experiment_name=experiment_name,
        experiment_config={
            "model_name": "mixed-metrics-model",
            "version": "1.0",
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
        experiment_metadata={"model_name": "mixed-metrics-model", "version": "1.0"},
        traces_amount=2,
        feedback_scores_amount=4,  # 2 regular + 2 task_span metrics
        prompts=[prompt],
    )

    retrieved_experiment = opik_client.get_experiment_by_name(experiment_name)
    experiment_items_contents = retrieved_experiment.get_items()
    assert len(experiment_items_contents) == 2

    expected_score_names = {
        "regular_equals",
        "regular_contains",
        "span_name_check",
        "span_input_check",
    }

    for item in experiment_items_contents:
        assert len(item.feedback_scores) == 4
        actual_score_names = {score["name"] for score in item.feedback_scores}
        assert actual_score_names == expected_score_names

        # Verify all metrics scored correctly (assuming perfect matches)
        for score in item.feedback_scores:
            assert score["value"] == 1.0
