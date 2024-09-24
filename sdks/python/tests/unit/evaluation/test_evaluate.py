import mock
import pytest

from opik.api_objects.dataset import dataset_item
from opik.api_objects import opik_client
from opik import evaluation, exceptions
from opik.evaluation import metrics
from ...testlib import backend_emulator_message_processor, ANY_BUT_NONE, assert_equal
from ...testlib.models import (
    TraceModel,
    FeedbackScoreModel,
)
from opik.message_processing import streamer_constructors


def test_evaluate_happyflow(fake_streamer):
    fake_message_processor_: (
        backend_emulator_message_processor.BackendEmulatorMessageProcessor
    )
    streamer, fake_message_processor_ = fake_streamer

    mock_dataset = mock.Mock()
    mock_dataset.name = "the-dataset-name"
    mock_dataset.get_all_items.return_value = [
        dataset_item.DatasetItem(
            id="dataset-item-id-1",
            input={"input": "say hello"},
            expected_output={"output": "hello"},
        ),
        dataset_item.DatasetItem(
            id="dataset-item-id-2",
            input={"input": "say bye"},
            expected_output={"output": "bye"},
        ),
    ]

    def say_task(dataset_item: dataset_item.DatasetItem):
        if dataset_item.input["input"] == "say hello":
            return {
                "output": "hello",
                "reference": dataset_item.expected_output["output"],
            }

        if dataset_item.input["input"] == "say bye":
            return {
                "output": "not bye",
                "reference": dataset_item.expected_output["output"],
            }

        raise Exception

    mock_construct_online_streamer = mock.Mock()
    mock_construct_online_streamer.return_value = streamer

    mock_experiment = mock.Mock()
    mock_create_experiment = mock.Mock()
    mock_create_experiment.return_value = mock_experiment

    with mock.patch.object(
        opik_client.Opik, "create_experiment", mock_create_experiment
    ):
        with mock.patch.object(
            streamer_constructors,
            "construct_online_streamer",
            mock_construct_online_streamer,
        ):
            evaluation.evaluate(
                dataset=mock_dataset,
                task=say_task,
                experiment_name="the-experiment-name",
                scoring_metrics=[metrics.Equals()],
                task_threads=1,
            )

    mock_create_experiment.assert_called_once_with(
        dataset_name="the-dataset-name",
        name="the-experiment-name",
        experiment_config=None,
    )
    mock_experiment.insert.assert_called_once_with(
        experiment_items=[mock.ANY, mock.ANY]
    )

    EXPECTED_TRACE_TREES = [
        TraceModel(
            id=ANY_BUT_NONE,
            name="evaluation_task",
            input={"input": "say hello"},
            output={
                "output": "hello",
                "reference": "hello",
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
            input={"input": "say bye"},
            output={
                "output": "not bye",
                "reference": "bye",
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
        EXPECTED_TRACE_TREES, fake_message_processor_.trace_trees
    ):
        assert_equal(expected_trace, actual_trace)


def test_evaluate___output_key_is_missing_in_task_output_dict__equals_metric_misses_output_argument__exception_raised():
    # Dataset is the only thing which is mocked for this test because
    # evaluate should raise an exception right after the first attempt
    # to compute Equals metric score.
    mock_dataset = mock.Mock()
    mock_dataset.name = "the-dataset-name"
    mock_dataset.get_all_items.return_value = [
        dataset_item.DatasetItem(
            id="dataset-item-id-1",
            input={"input": "say hello"},
            expected_output={"output": "hello"},
        ),
    ]

    def say_task(dataset_item: dataset_item.DatasetItem):
        if dataset_item.input["input"] == "say hello":
            return {
                "the-key-that-is-not-named-output": "hello",
                "reference": dataset_item.expected_output["output"],
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

    mock_dataset.get_all_items.assert_called_once()
