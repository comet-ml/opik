import mock
import pytest
from typing import Dict, Any

from opik.api_objects.dataset import dataset_item
from opik.api_objects import opik_client
from opik import evaluation, exceptions, url_helpers
from opik.evaluation import metrics
from ...testlib import ANY_BUT_NONE, assert_equal
from ...testlib.models import (
    TraceModel,
    FeedbackScoreModel,
)


def test_evaluate_happyflow(fake_backend):
    mock_dataset = mock.MagicMock(spec=["__internal_api__get_items_as_dataclasses__"])
    mock_dataset.name = "the-dataset-name"
    mock_dataset.__internal_api__get_items_as_dataclasses__.return_value = [
        dataset_item.DatasetItem(
            id="dataset-item-id-1",
            input={"message": "say hello"},
            reference="hello",
        ),
        dataset_item.DatasetItem(
            id="dataset-item-id-2",
            input={"message": "say bye"},
            reference="bye",
        ),
    ]

    def say_task(dataset_item: Dict[str, Any]):
        if dataset_item["input"]["message"] == "say hello":
            return {"output": "hello"}

        if dataset_item["input"]["message"] == "say bye":
            return {"output": "not bye"}

        raise Exception

    mock_experiment = mock.Mock()
    mock_create_experiment = mock.Mock()
    mock_create_experiment.return_value = mock_experiment

    mock_get_experiment_url = mock.Mock()
    mock_get_experiment_url.return_value = "any_url"

    with mock.patch.object(
        opik_client.Opik, "create_experiment", mock_create_experiment
    ):
        with mock.patch.object(
            url_helpers, "get_experiment_url", mock_get_experiment_url
        ):
            evaluation.evaluate(
                dataset=mock_dataset,
                task=say_task,
                experiment_name="the-experiment-name",
                scoring_metrics=[metrics.Equals()],
                task_threads=1,
            )

    mock_dataset.__internal_api__get_items_as_dataclasses__.assert_called_once()

    mock_create_experiment.assert_called_once_with(
        dataset_name="the-dataset-name",
        name="the-experiment-name",
        experiment_config=None,
        prompt=None,
    )
    mock_experiment.insert.assert_called_once_with(
        experiment_items=[mock.ANY, mock.ANY]
    )

    EXPECTED_TRACE_TREES = [
        TraceModel(
            id=ANY_BUT_NONE,
            name="evaluation_task",
            input={
                "input": {"message": "say hello"},
                "reference": "hello",
            },
            output={
                "output": "hello",
            },
            start_time=ANY_BUT_NONE,
            end_time=ANY_BUT_NONE,
            spans=[],
            feedback_scores=[
                FeedbackScoreModel(
                    id=ANY_BUT_NONE,
                    name="equals_metric",
                    value=1.0,
                )
            ],
        ),
        TraceModel(
            id=ANY_BUT_NONE,
            name="evaluation_task",
            input={
                "input": {"message": "say bye"},
                "reference": "bye",
            },
            output={
                "output": "not bye",
            },
            start_time=ANY_BUT_NONE,
            end_time=ANY_BUT_NONE,
            spans=[],
            feedback_scores=[
                FeedbackScoreModel(
                    id=ANY_BUT_NONE,
                    name="equals_metric",
                    value=0.0,
                )
            ],
        ),
    ]
    for expected_trace, actual_trace in zip(
        EXPECTED_TRACE_TREES, fake_backend.trace_trees
    ):
        assert_equal(expected_trace, actual_trace)


def test_evaluate_with_scoring_key_mapping(fake_backend):
    mock_dataset = mock.MagicMock(spec=["__internal_api__get_items_as_dataclasses__"])
    mock_dataset.name = "the-dataset-name"
    mock_dataset.__internal_api__get_items_as_dataclasses__.return_value = [
        dataset_item.DatasetItem(
            id="dataset-item-id-1",
            input={"message": "say hello"},
            expected_output={"message": "hello"},
        ),
        dataset_item.DatasetItem(
            id="dataset-item-id-2",
            input={"message": "say bye"},
            expected_output={"message": "bye"},
        ),
    ]

    def say_task(dataset_item: Dict[str, Any]):
        if dataset_item["input"]["message"] == "say hello":
            return {"result": "hello"}

        if dataset_item["input"]["message"] == "say bye":
            return {"result": "not bye"}

        raise Exception

    mock_experiment = mock.Mock()
    mock_create_experiment = mock.Mock()
    mock_create_experiment.return_value = mock_experiment

    mock_get_experiment_url = mock.Mock()
    mock_get_experiment_url.return_value = "any_url"

    with mock.patch.object(
        opik_client.Opik, "create_experiment", mock_create_experiment
    ):
        with mock.patch.object(
            url_helpers, "get_experiment_url", mock_get_experiment_url
        ):
            evaluation.evaluate(
                dataset=mock_dataset,
                task=say_task,
                experiment_name="the-experiment-name",
                scoring_metrics=[metrics.Equals()],
                task_threads=1,
                scoring_key_mapping={
                    "output": "result",
                    "reference": lambda x: x["expected_output"]["message"],
                },
            )

    mock_dataset.__internal_api__get_items_as_dataclasses__.assert_called_once()

    mock_create_experiment.assert_called_once_with(
        dataset_name="the-dataset-name",
        name="the-experiment-name",
        experiment_config=None,
        prompt=None,
    )
    mock_experiment.insert.assert_called_once_with(
        experiment_items=[mock.ANY, mock.ANY]
    )

    EXPECTED_TRACE_TREES = [
        TraceModel(
            id=ANY_BUT_NONE,
            name="evaluation_task",
            input={
                "input": {"message": "say hello"},
                "expected_output": {"message": "hello"},
            },
            output={
                "result": "hello",
            },
            start_time=ANY_BUT_NONE,
            end_time=ANY_BUT_NONE,
            spans=[],
            feedback_scores=[
                FeedbackScoreModel(
                    id=ANY_BUT_NONE,
                    name="equals_metric",
                    value=1.0,
                )
            ],
        ),
        TraceModel(
            id=ANY_BUT_NONE,
            name="evaluation_task",
            input={
                "input": {"message": "say bye"},
                "expected_output": {"message": "bye"},
            },
            output={
                "result": "not bye",
            },
            start_time=ANY_BUT_NONE,
            end_time=ANY_BUT_NONE,
            spans=[],
            feedback_scores=[
                FeedbackScoreModel(
                    id=ANY_BUT_NONE,
                    name="equals_metric",
                    value=0.0,
                )
            ],
        ),
    ]
    for expected_trace, actual_trace in zip(
        EXPECTED_TRACE_TREES, fake_backend.trace_trees
    ):
        assert_equal(expected_trace, actual_trace)


def test_evaluate___output_key_is_missing_in_task_output_dict__equals_metric_misses_output_argument__exception_raised():
    # Dataset is the only thing which is mocked for this test because
    # evaluate should raise an exception right after the first attempt
    # to compute Equals metric score.
    mock_dataset = mock.MagicMock(spec=["__internal_api__get_items_as_dataclasses__"])
    mock_dataset.name = "the-dataset-name"
    mock_dataset.__internal_api__get_items_as_dataclasses__.return_value = [
        dataset_item.DatasetItem(
            id="dataset-item-id-1",
            input={"message": "say hello"},
            expected_output={"message": "hello"},
        ),
    ]

    def say_task(dataset_item: Dict[str, Any]):
        if dataset_item["input"]["message"] == "say hello":
            return {
                "the-key-that-is-not-named-output": "hello",
                "reference": dataset_item["expected_output"]["message"],
            }
        raise Exception

    with pytest.raises(exceptions.ScoreMethodMissingArguments):
        evaluation.evaluate(
            dataset=mock_dataset,
            task=say_task,
            experiment_name="the-experiment-name",
            scoring_metrics=[metrics.Equals()],
            task_threads=1,
        )

    mock_dataset.__internal_api__get_items_as_dataclasses__.assert_called_once()
