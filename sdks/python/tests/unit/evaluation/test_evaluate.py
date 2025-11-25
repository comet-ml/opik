from typing import Any, Dict, List
from unittest import mock
import pytest

import opik
from opik import evaluation, exceptions, url_helpers
from opik.api_objects import opik_client
from opik.api_objects.dataset import dataset_item
from opik.evaluation import (
    evaluator as evaluator_module,
    metrics,
    samplers,
    score_statistics,
)
from opik.evaluation.metrics import score_result
from opik.evaluation.models import models_factory
from opik.evaluation.evaluator import _build_prompt_evaluation_task

from ...testlib import ANY_BUT_NONE, ANY_STRING, ANY_LIST, SpanModel, assert_equal
from ...testlib.models import FeedbackScoreModel, TraceModel


def test_evaluate__happyflow(
    fake_backend,
):
    mock_dataset = mock.MagicMock(
        spec=["__internal_api__get_items_as_dataclasses__", "id"]
    )
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

    mock_get_experiment_url_by_id = mock.Mock()
    mock_get_experiment_url_by_id.return_value = "any_url"

    with mock.patch.object(
        opik_client.Opik, "create_experiment", mock_create_experiment
    ):
        with mock.patch.object(
            url_helpers, "get_experiment_url_by_id", mock_get_experiment_url_by_id
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
        prompts=None,
    )

    mock_experiment.insert.assert_has_calls(
        [
            mock.call(experiment_items_references=mock.ANY),
            mock.call(experiment_items_references=mock.ANY),
        ]
    )
    EXPECTED_TRACE_TREES = [
        TraceModel(
            id=ANY_BUT_NONE,
            name="evaluation_task",
            input={
                "input": {"message": "say hello"},
                "reference": "hello",
                "id": "dataset-item-id-1",
            },
            output={
                "output": "hello",
            },
            start_time=ANY_BUT_NONE,
            end_time=ANY_BUT_NONE,
            last_updated_at=ANY_BUT_NONE,
            spans=[
                SpanModel(
                    id=ANY_BUT_NONE,
                    type="general",
                    name="say_task",
                    input={
                        "dataset_item": {
                            "input": {"message": "say hello"},
                            "reference": "hello",
                            "id": "dataset-item-id-1",
                        },
                    },
                    output={
                        "output": "hello",
                    },
                    start_time=ANY_BUT_NONE,
                    end_time=ANY_BUT_NONE,
                    spans=[],
                ),
                SpanModel(
                    id=ANY_BUT_NONE,
                    type="general",
                    name="metrics_calculation",
                    input={
                        "test_case_": ANY_BUT_NONE,
                        "trial_id": 0,
                    },
                    output={
                        "output": ANY_BUT_NONE,
                    },
                    start_time=ANY_BUT_NONE,
                    end_time=ANY_BUT_NONE,
                    spans=[
                        SpanModel(
                            id=ANY_BUT_NONE,
                            type="general",
                            name="equals_metric",
                            input={
                                "ignored_kwargs": {
                                    "input": {"message": "say hello"},
                                    "id": "dataset-item-id-1",
                                },
                                "output": "hello",
                                "reference": "hello",
                            },
                            output={
                                "output": ANY_BUT_NONE,
                            },
                            start_time=ANY_BUT_NONE,
                            end_time=ANY_BUT_NONE,
                            spans=[],
                        ),
                    ],
                ),
            ],
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
                "id": "dataset-item-id-2",
            },
            output={
                "output": "not bye",
            },
            start_time=ANY_BUT_NONE,
            end_time=ANY_BUT_NONE,
            last_updated_at=ANY_BUT_NONE,
            spans=[
                SpanModel(
                    id=ANY_BUT_NONE,
                    type="general",
                    name="say_task",
                    input={
                        "dataset_item": {
                            "input": {"message": "say bye"},
                            "reference": "bye",
                            "id": "dataset-item-id-2",
                        }
                    },
                    output={"output": "not bye"},
                    start_time=ANY_BUT_NONE,
                    end_time=ANY_BUT_NONE,
                    spans=[],
                ),
                SpanModel(
                    id=ANY_BUT_NONE,
                    type="general",
                    name="metrics_calculation",
                    input={
                        "test_case_": ANY_BUT_NONE,
                        "trial_id": 0,
                    },
                    output={"output": ANY_BUT_NONE},
                    start_time=ANY_BUT_NONE,
                    end_time=ANY_BUT_NONE,
                    spans=[
                        SpanModel(
                            id=ANY_BUT_NONE,
                            type="general",
                            name="equals_metric",
                            input={
                                "ignored_kwargs": {
                                    "input": {"message": "say bye"},
                                    "id": "dataset-item-id-2",
                                },
                                "output": "not bye",
                                "reference": "bye",
                            },
                            output={
                                "output": ANY_BUT_NONE,
                            },
                            start_time=ANY_BUT_NONE,
                            end_time=ANY_BUT_NONE,
                            spans=[],
                        )
                    ],
                ),
            ],
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


def test_evaluate_with_scoring_key_mapping(
    fake_backend,
):
    mock_dataset = mock.MagicMock(
        spec=["__internal_api__get_items_as_dataclasses__", "id"]
    )
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

    mock_get_experiment_url_by_id = mock.Mock()
    mock_get_experiment_url_by_id.return_value = "any_url"

    with mock.patch.object(
        opik_client.Opik, "create_experiment", mock_create_experiment
    ):
        with mock.patch.object(
            url_helpers, "get_experiment_url_by_id", mock_get_experiment_url_by_id
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
        prompts=None,
    )
    mock_experiment.insert.assert_has_calls(
        [
            mock.call(experiment_items_references=mock.ANY),
            mock.call(experiment_items_references=mock.ANY),
        ]
    )

    EXPECTED_TRACE_TREES = [
        TraceModel(
            id=ANY_BUT_NONE,
            name="evaluation_task",
            input={
                "input": {"message": "say hello"},
                "expected_output": {"message": "hello"},
                "id": "dataset-item-id-1",
            },
            output={
                "result": "hello",
            },
            start_time=ANY_BUT_NONE,
            end_time=ANY_BUT_NONE,
            last_updated_at=ANY_BUT_NONE,
            spans=[
                SpanModel(
                    id=ANY_BUT_NONE,
                    type="general",
                    name="say_task",
                    input={
                        "dataset_item": {
                            "input": {"message": "say hello"},
                            "expected_output": {"message": "hello"},
                            "id": "dataset-item-id-1",
                        },
                    },
                    output={
                        "result": "hello",
                    },
                    start_time=ANY_BUT_NONE,
                    end_time=ANY_BUT_NONE,
                    spans=[],
                ),
                SpanModel(
                    id=ANY_BUT_NONE,
                    type="general",
                    name="metrics_calculation",
                    input={
                        "test_case_": ANY_BUT_NONE,
                        "trial_id": 0,
                    },
                    output={
                        "output": ANY_BUT_NONE,
                    },
                    start_time=ANY_BUT_NONE,
                    end_time=ANY_BUT_NONE,
                    spans=[
                        SpanModel(
                            id=ANY_BUT_NONE,
                            type="general",
                            name="equals_metric",
                            input={
                                "ignored_kwargs": {
                                    "expected_output": {"message": "hello"},
                                    "input": {"message": "say hello"},
                                    "result": "hello",
                                    "id": "dataset-item-id-1",
                                },
                                "output": "hello",
                                "reference": "hello",
                            },
                            output={
                                "output": ANY_BUT_NONE,
                            },
                            start_time=ANY_BUT_NONE,
                            end_time=ANY_BUT_NONE,
                            spans=[],
                        ),
                    ],
                ),
            ],
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
                "id": "dataset-item-id-2",
            },
            output={
                "result": "not bye",
            },
            start_time=ANY_BUT_NONE,
            end_time=ANY_BUT_NONE,
            last_updated_at=ANY_BUT_NONE,
            spans=[
                SpanModel(
                    id=ANY_BUT_NONE,
                    type="general",
                    name="say_task",
                    input={
                        "dataset_item": {
                            "input": {"message": "say bye"},
                            "expected_output": {"message": "bye"},
                            "id": "dataset-item-id-2",
                        },
                    },
                    output={
                        "result": "not bye",
                    },
                    start_time=ANY_BUT_NONE,
                    end_time=ANY_BUT_NONE,
                    spans=[],
                ),
                SpanModel(
                    id=ANY_BUT_NONE,
                    type="general",
                    name="metrics_calculation",
                    input={
                        "test_case_": ANY_BUT_NONE,
                        "trial_id": 0,
                    },
                    output={
                        "output": ANY_BUT_NONE,
                    },
                    start_time=ANY_BUT_NONE,
                    end_time=ANY_BUT_NONE,
                    spans=[
                        SpanModel(
                            id=ANY_BUT_NONE,
                            type="general",
                            name="equals_metric",
                            input={
                                "ignored_kwargs": {
                                    "expected_output": {"message": "bye"},
                                    "input": {"message": "say bye"},
                                    "result": "not bye",
                                    "id": "dataset-item-id-2",
                                },
                                "output": "not bye",
                                "reference": "bye",
                            },
                            output={
                                "output": ANY_BUT_NONE,
                            },
                            start_time=ANY_BUT_NONE,
                            end_time=ANY_BUT_NONE,
                            spans=[],
                        )
                    ],
                ),
            ],
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
    mock_dataset = mock.MagicMock(
        spec=["__internal_api__get_items_as_dataclasses__", "id"]
    )
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

    mock_experiment = mock.Mock()
    mock_create_experiment = mock.Mock()
    mock_create_experiment.return_value = mock_experiment

    mock_get_experiment_url_by_id = mock.Mock()
    mock_get_experiment_url_by_id.return_value = "any_url"
    with mock.patch.object(
        opik_client.Opik, "create_experiment", mock_create_experiment
    ):
        with mock.patch.object(
            url_helpers, "get_experiment_url_by_id", mock_get_experiment_url_by_id
        ):
            with pytest.raises(exceptions.ScoreMethodMissingArguments):
                evaluation.evaluate(
                    dataset=mock_dataset,
                    task=say_task,
                    experiment_name="the-experiment-name",
                    scoring_metrics=[metrics.Equals()],
                    task_threads=1,
                )

    mock_dataset.__internal_api__get_items_as_dataclasses__.assert_called_once()


def test_evaluate__exception_raised_from_the_task__error_info_added_to_the_trace(
    fake_backend,
):
    mock_dataset = mock.MagicMock(
        spec=["__internal_api__get_items_as_dataclasses__", "id"]
    )
    mock_dataset.name = "the-dataset-name"
    mock_dataset.__internal_api__get_items_as_dataclasses__.return_value = [
        dataset_item.DatasetItem(
            id="dataset-item-id-1",
            input={"message": "say hello"},
            reference="hello",
        ),
    ]

    def say_task(dataset_item: Dict[str, Any]):
        raise Exception("some-error-message")

    mock_experiment = mock.Mock()
    mock_create_experiment = mock.Mock()
    mock_create_experiment.return_value = mock_experiment

    mock_get_experiment_url_by_id = mock.Mock()
    mock_get_experiment_url_by_id.return_value = "any_url"

    with mock.patch.object(
        opik_client.Opik, "create_experiment", mock_create_experiment
    ):
        with mock.patch.object(
            url_helpers, "get_experiment_url_by_id", mock_get_experiment_url_by_id
        ):
            with pytest.raises(Exception):
                evaluation.evaluate(
                    dataset=mock_dataset,
                    task=say_task,
                    experiment_name="the-experiment-name",
                    scoring_metrics=[],
                    task_threads=1,
                )
            opik.flush_tracker()

    mock_dataset.__internal_api__get_items_as_dataclasses__.assert_called_once()

    mock_create_experiment.assert_called_once_with(
        dataset_name="the-dataset-name",
        name="the-experiment-name",
        experiment_config=None,
        prompts=None,
    )

    mock_experiment.insert.assert_called_once_with(
        experiment_items_references=[mock.ANY]
    )
    EXPECTED_TRACE_TREE = TraceModel(
        id=ANY_BUT_NONE,
        name="evaluation_task",
        input={
            "input": {"message": "say hello"},
            "reference": "hello",
            "id": "dataset-item-id-1",
        },
        output=None,
        start_time=ANY_BUT_NONE,
        end_time=ANY_BUT_NONE,
        last_updated_at=ANY_BUT_NONE,
        error_info={
            "exception_type": "Exception",
            "message": "some-error-message",
            "traceback": ANY_STRING,
        },
        spans=[
            SpanModel(
                id=ANY_BUT_NONE,
                type="general",
                name="say_task",
                input={
                    "dataset_item": {
                        "input": {"message": "say hello"},
                        "reference": "hello",
                        "id": "dataset-item-id-1",
                    }
                },
                error_info={
                    "exception_type": "Exception",
                    "message": "some-error-message",
                    "traceback": ANY_STRING,
                },
                output=None,
                start_time=ANY_BUT_NONE,
                end_time=ANY_BUT_NONE,
                spans=[],
            ),
        ],
    )

    assert_equal(EXPECTED_TRACE_TREE, fake_backend.trace_trees[0])


def test_evaluate__with_random_sampler__happy_flow(
    fake_backend,
):
    # Creates a dataset with 5 items and then evaluates it using a random dataset sampler with 3 samples limit.
    # Checks that only three samples are selected and that the metrics are computed for the three samples.
    mock_dataset = mock.MagicMock(
        spec=["__internal_api__get_items_as_dataclasses__", "id"]
    )
    mock_dataset.name = "the-dataset-name"
    mock_dataset.__internal_api__get_items_as_dataclasses__.return_value = [
        dataset_item.DatasetItem(
            id="dataset-item-id-1",
            input={"message": "say hello"},
            reference="hello",
        ),
        dataset_item.DatasetItem(
            id="dataset-item-id-2",
            input={"message": "hi there"},
            reference="hello",
        ),
        dataset_item.DatasetItem(
            id="dataset-item-id-3",
            input={"message": "how are you"},
            reference="hello",
        ),
        dataset_item.DatasetItem(
            id="dataset-item-id-4",
            input={"message": "say bye"},
            reference="bye",
        ),
        dataset_item.DatasetItem(
            id="dataset-item-id-5",
            input={"message": "see ya"},
            reference="bye",
        ),
    ]

    def say_task(dataset_item: Dict[str, Any]):
        if dataset_item["reference"] == "hello":
            return {"output": "hello"}

        if dataset_item["reference"] == "bye":
            return {"output": "not bye"}

        raise Exception

    mock_experiment = mock.Mock()
    mock_create_experiment = mock.Mock()
    mock_create_experiment.return_value = mock_experiment

    mock_get_experiment_url_by_id = mock.Mock()
    mock_get_experiment_url_by_id.return_value = "any_url"

    # create a random sampler with 3 samples limit
    sampler = samplers.RandomDatasetSampler(max_samples=3)

    with mock.patch.object(
        opik_client.Opik, "create_experiment", mock_create_experiment
    ):
        with mock.patch.object(
            url_helpers, "get_experiment_url_by_id", mock_get_experiment_url_by_id
        ):
            evaluation.evaluate(
                dataset=mock_dataset,
                task=say_task,
                experiment_name="the-experiment-name",
                scoring_metrics=[metrics.Equals()],
                task_threads=1,
                dataset_sampler=sampler,
            )

    mock_dataset.__internal_api__get_items_as_dataclasses__.assert_called_once()

    mock_create_experiment.assert_called_once_with(
        dataset_name="the-dataset-name",
        name="the-experiment-name",
        experiment_config=None,
        prompts=None,
    )

    mock_experiment.insert.assert_has_calls(
        [
            mock.call(experiment_items_references=mock.ANY),
            mock.call(experiment_items_references=mock.ANY),
        ]
    )

    # Due to the random nature of the sampler, we need to verify the structure
    # and that exactly 3 samples were selected, but not specific dataset items
    actual_traces = fake_backend.trace_trees
    assert len(actual_traces) == 3, f"Expected 3 traces, got {len(actual_traces)}"

    # Verify each trace has the expected values
    #  Checks business logic consistency based on the reference value:
    #     - If reference is "hello" → output should be "hello" and score should be 1.0
    #     - If reference is "bye" → output should be "not bye" and score should be 0.0
    for actual_trace in actual_traces:
        # Verify feedback scores
        assert len(actual_trace.feedback_scores) == 1
        feedback_score = actual_trace.feedback_scores[0]
        assert feedback_score.name == "equals_metric"
        assert feedback_score.value in [0.0, 1.0]  # Should be either 0 or 1

        # Verify task behavior based on reference value
        reference = actual_trace.input["reference"]
        expected_output = "hello" if reference == "hello" else "not bye"
        expected_score = 1.0 if reference == "hello" else 0.0

        assert actual_trace.output["output"] == expected_output
        assert feedback_score.value == expected_score


def test_build_prompt_evaluation_task_logs_when_vision_missing() -> None:
    model = mock.Mock()
    model.model_name = "text-only-model"
    messages = [
        {
            "role": "user",
            "content": [
                {"type": "text", "text": "Describe the picture"},
                {"type": "image_url", "image_url": {"url": "{{image_url}}"}},
            ],
        }
    ]

    with mock.patch.object(evaluator_module.LOGGER, "warning") as warning_mock:
        _build_prompt_evaluation_task(model=model, messages=messages)

    warning_mock.assert_called_once()
    message_template, model_name, modal_list, doc_url = warning_mock.call_args[0]
    assert "does not support %s content" in message_template
    assert model_name == "text-only-model"
    assert modal_list == "vision"
    assert "comet.com/docs/opik" in doc_url


def test_evaluate_prompt_happyflow(
    fake_backend,
):
    MODEL_NAME = "gpt-3.5-turbo"

    mock_dataset = mock.MagicMock(
        spec=["__internal_api__get_items_as_dataclasses__", "id"]
    )
    mock_dataset.name = "the-dataset-name"
    mock_dataset.__internal_api__get_items_as_dataclasses__.return_value = [
        dataset_item.DatasetItem(
            id="dataset-item-id-1",
            question="Hello, world!",
            reference="Hello, world!",
        ),
        dataset_item.DatasetItem(
            id="dataset-item-id-2",
            question="What is the capital of France?",
            reference="Paris",
        ),
    ]

    mock_experiment = mock.Mock()
    mock_create_experiment = mock.Mock()
    mock_create_experiment.return_value = mock_experiment

    mock_get_experiment_url_by_id = mock.Mock()
    mock_get_experiment_url_by_id.return_value = "any_url"

    mock_models_factory_get = mock.Mock()
    mock_model = mock.Mock()
    mock_model.model_name = MODEL_NAME
    mock_model.generate_provider_response.return_value = mock.Mock(
        choices=[mock.Mock(message=mock.Mock(content="Hello, world!"))]
    )
    mock_models_factory_get.return_value = mock_model

    with mock.patch.object(
        opik_client.Opik, "create_experiment", mock_create_experiment
    ):
        with mock.patch.object(
            url_helpers, "get_experiment_url_by_id", mock_get_experiment_url_by_id
        ):
            with mock.patch.object(
                models_factory,
                "get",
                mock_models_factory_get,
            ):
                evaluation.evaluate_prompt(
                    dataset=mock_dataset,
                    messages=[
                        {"role": "user", "content": "LLM response: {{input}}"},
                    ],
                    experiment_name="the-experiment-name",
                    model=MODEL_NAME,
                    scoring_metrics=[metrics.Equals()],
                    task_threads=1,
                )

    mock_dataset.__internal_api__get_items_as_dataclasses__.assert_called_once()

    mock_create_experiment.assert_called_once_with(
        dataset_name="the-dataset-name",
        name="the-experiment-name",
        experiment_config={
            "prompt_template": [{"role": "user", "content": "LLM response: {{input}}"}],
            "model": "gpt-3.5-turbo",
        },
        prompts=None,
    )

    mock_experiment.insert.assert_has_calls(
        [
            mock.call(experiment_items_references=mock.ANY),
            mock.call(experiment_items_references=mock.ANY),
        ]
    )
    EXPECTED_TRACE_TREES = [
        TraceModel(
            id=ANY_BUT_NONE,
            name="evaluation_task",
            input={
                "question": "Hello, world!",
                "reference": "Hello, world!",
                "id": "dataset-item-id-1",
            },
            output={
                "input": [{"role": "user", "content": "LLM response: {{input}}"}],
                "output": "Hello, world!",
            },
            start_time=ANY_BUT_NONE,
            end_time=ANY_BUT_NONE,
            last_updated_at=ANY_BUT_NONE,
            spans=[
                SpanModel(
                    id=ANY_BUT_NONE,
                    type="general",
                    name="_prompt_evaluation_task",
                    input={
                        "prompt_variables": {
                            "question": "Hello, world!",
                            "reference": "Hello, world!",
                            "id": "dataset-item-id-1",
                        }
                    },
                    output={
                        "input": [
                            {"role": "user", "content": "LLM response: {{input}}"}
                        ],
                        "output": "Hello, world!",
                    },
                    start_time=ANY_BUT_NONE,
                    end_time=ANY_BUT_NONE,
                    spans=[],
                ),
                SpanModel(
                    id=ANY_BUT_NONE,
                    type="general",
                    name="metrics_calculation",
                    input=ANY_BUT_NONE,
                    output=ANY_BUT_NONE,
                    start_time=ANY_BUT_NONE,
                    end_time=ANY_BUT_NONE,
                    spans=[ANY_BUT_NONE],
                ),
            ],
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
                "question": "What is the capital of France?",
                "reference": "Paris",
                "id": "dataset-item-id-2",
            },
            output={
                "input": [{"role": "user", "content": "LLM response: {{input}}"}],
                "output": "Hello, world!",
            },
            start_time=ANY_BUT_NONE,
            end_time=ANY_BUT_NONE,
            last_updated_at=ANY_BUT_NONE,
            spans=[
                SpanModel(
                    id=ANY_BUT_NONE,
                    type="general",
                    name="_prompt_evaluation_task",
                    input={
                        "prompt_variables": {
                            "question": "What is the capital of France?",
                            "reference": "Paris",
                            "id": "dataset-item-id-2",
                        }
                    },
                    output={
                        "input": [
                            {"role": "user", "content": "LLM response: {{input}}"}
                        ],
                        "output": "Hello, world!",
                    },
                    start_time=ANY_BUT_NONE,
                    end_time=ANY_BUT_NONE,
                    spans=[],
                ),
                SpanModel(
                    id=ANY_BUT_NONE,
                    type="general",
                    name="metrics_calculation",
                    input=ANY_BUT_NONE,
                    output=ANY_BUT_NONE,
                    start_time=ANY_BUT_NONE,
                    end_time=ANY_BUT_NONE,
                    spans=[ANY_BUT_NONE],
                ),
            ],
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


def test_evaluate__aggregated_metric__happy_flow(
    fake_backend,
):
    mock_dataset = mock.MagicMock(
        spec=["__internal_api__get_items_as_dataclasses__", "id"]
    )
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

    mock_get_experiment_url_by_id = mock.Mock()
    mock_get_experiment_url_by_id.return_value = "any_url"

    def aggregator(results: List[score_result.ScoreResult]) -> score_result.ScoreResult:
        value = sum([result.value for result in results])
        return score_result.ScoreResult(name="aggregated_metric_result", value=value)

    metrics_list = [metrics.Equals(), metrics.Contains()]
    aggregated_metric = metrics.AggregatedMetric(
        name="aggregated_metric",
        metrics=metrics_list,
        aggregator=aggregator,
    )

    with mock.patch.object(
        opik_client.Opik, "create_experiment", mock_create_experiment
    ):
        with mock.patch.object(
            url_helpers, "get_experiment_url_by_id", mock_get_experiment_url_by_id
        ):
            evaluation.evaluate(
                dataset=mock_dataset,
                task=say_task,
                experiment_name="the-experiment-name",
                scoring_metrics=[aggregated_metric],
                task_threads=1,
            )

    mock_dataset.__internal_api__get_items_as_dataclasses__.assert_called_once()

    mock_create_experiment.assert_called_once_with(
        dataset_name="the-dataset-name",
        name="the-experiment-name",
        experiment_config=None,
        prompts=None,
    )

    mock_experiment.insert.assert_has_calls(
        [
            mock.call(experiment_items_references=mock.ANY),
            mock.call(experiment_items_references=mock.ANY),
        ]
    )
    EXPECTED_TRACE_TREES = [
        TraceModel(
            id=ANY_BUT_NONE,
            name="evaluation_task",
            input={
                "input": {"message": "say hello"},
                "reference": "hello",
                "id": "dataset-item-id-1",
            },
            output={
                "output": "hello",
            },
            start_time=ANY_BUT_NONE,
            end_time=ANY_BUT_NONE,
            last_updated_at=ANY_BUT_NONE,
            spans=[
                SpanModel(
                    id=ANY_BUT_NONE,
                    type="general",
                    name="say_task",
                    input={
                        "dataset_item": {
                            "input": {"message": "say hello"},
                            "reference": "hello",
                            "id": "dataset-item-id-1",
                        },
                    },
                    output={
                        "output": "hello",
                    },
                    start_time=ANY_BUT_NONE,
                    end_time=ANY_BUT_NONE,
                    spans=[],
                ),
                SpanModel(
                    id=ANY_BUT_NONE,
                    type="general",
                    name="metrics_calculation",
                    input={
                        "test_case_": ANY_BUT_NONE,
                        "trial_id": 0,
                    },
                    output={
                        "output": ANY_BUT_NONE,
                    },
                    start_time=ANY_BUT_NONE,
                    end_time=ANY_BUT_NONE,
                    spans=[
                        SpanModel(
                            id=ANY_BUT_NONE,
                            type="general",
                            name="aggregated_metric",
                            input={
                                "kwargs": {
                                    "input": {"message": "say hello"},
                                    "reference": "hello",
                                    "output": "hello",
                                    "id": "dataset-item-id-1",
                                }
                            },
                            output={
                                "output": ANY_BUT_NONE,
                            },
                            start_time=ANY_BUT_NONE,
                            end_time=ANY_BUT_NONE,
                            spans=[
                                SpanModel(
                                    id=ANY_BUT_NONE,
                                    type="general",
                                    name="equals_metric",
                                    input={
                                        "ignored_kwargs": {
                                            "input": {"message": "say hello"},
                                            "id": "dataset-item-id-1",
                                        },
                                        "output": "hello",
                                        "reference": "hello",
                                    },
                                    output={
                                        "output": score_result.ScoreResult(
                                            name="equals_metric",
                                            value=1.0,
                                        ).__dict__,
                                    },
                                    start_time=ANY_BUT_NONE,
                                    end_time=ANY_BUT_NONE,
                                ),
                                SpanModel(
                                    id=ANY_BUT_NONE,
                                    type="general",
                                    name="contains_metric",
                                    input={
                                        "ignored_kwargs": {
                                            "input": {"message": "say hello"},
                                            "id": "dataset-item-id-1",
                                        },
                                        "output": "hello",
                                        "reference": "hello",
                                    },
                                    output={
                                        "output": score_result.ScoreResult(
                                            name="contains_metric",
                                            value=1.0,
                                        ).__dict__,
                                    },
                                    start_time=ANY_BUT_NONE,
                                    end_time=ANY_BUT_NONE,
                                ),
                            ],
                        ),
                    ],
                ),
            ],
            feedback_scores=[
                # both contains and equals metrics will add to an aggregated result
                FeedbackScoreModel(
                    id=ANY_BUT_NONE,
                    name="aggregated_metric_result",
                    value=2.0,
                )
            ],
        ),
        TraceModel(
            id=ANY_BUT_NONE,
            name="evaluation_task",
            input={
                "input": {"message": "say bye"},
                "reference": "bye",
                "id": "dataset-item-id-2",
            },
            output={
                "output": "not bye",
            },
            start_time=ANY_BUT_NONE,
            end_time=ANY_BUT_NONE,
            last_updated_at=ANY_BUT_NONE,
            spans=[
                SpanModel(
                    id=ANY_BUT_NONE,
                    type="general",
                    name="say_task",
                    input={
                        "dataset_item": {
                            "input": {"message": "say bye"},
                            "reference": "bye",
                            "id": "dataset-item-id-2",
                        }
                    },
                    output={"output": "not bye"},
                    start_time=ANY_BUT_NONE,
                    end_time=ANY_BUT_NONE,
                    spans=[],
                ),
                SpanModel(
                    id=ANY_BUT_NONE,
                    type="general",
                    name="metrics_calculation",
                    input={
                        "test_case_": ANY_BUT_NONE,
                        "trial_id": 0,
                    },
                    output={"output": ANY_BUT_NONE},
                    start_time=ANY_BUT_NONE,
                    end_time=ANY_BUT_NONE,
                    spans=[
                        SpanModel(
                            id=ANY_BUT_NONE,
                            type="general",
                            name="aggregated_metric",
                            input={
                                "kwargs": {
                                    "input": {"message": "say bye"},
                                    "reference": "bye",
                                    "output": "not bye",
                                    "id": "dataset-item-id-2",
                                }
                            },
                            output={
                                "output": ANY_BUT_NONE,
                            },
                            start_time=ANY_BUT_NONE,
                            end_time=ANY_BUT_NONE,
                            spans=[
                                SpanModel(
                                    id=ANY_BUT_NONE,
                                    type="general",
                                    name="equals_metric",
                                    input={
                                        "ignored_kwargs": {
                                            "input": {"message": "say bye"},
                                            "id": "dataset-item-id-2",
                                        },
                                        "reference": "bye",
                                        "output": "not bye",
                                    },
                                    output={
                                        "output": score_result.ScoreResult(
                                            name="equals_metric",
                                            value=0.0,
                                        ).__dict__,
                                    },
                                    start_time=ANY_BUT_NONE,
                                    end_time=ANY_BUT_NONE,
                                ),
                                SpanModel(
                                    id=ANY_BUT_NONE,
                                    type="general",
                                    name="contains_metric",
                                    input={
                                        "ignored_kwargs": {
                                            "input": {"message": "say bye"},
                                            "id": "dataset-item-id-2",
                                        },
                                        "reference": "bye",
                                        "output": "not bye",
                                    },
                                    output={
                                        "output": score_result.ScoreResult(
                                            name="contains_metric",
                                            value=1.0,
                                        ).__dict__,
                                    },
                                    start_time=ANY_BUT_NONE,
                                    end_time=ANY_BUT_NONE,
                                ),
                            ],
                        )
                    ],
                ),
            ],
            feedback_scores=[
                # only contains metric will add to an aggregated result
                FeedbackScoreModel(
                    id=ANY_BUT_NONE,
                    name="aggregated_metric_result",
                    value=1.0,
                )
            ],
        ),
    ]
    for expected_trace, actual_trace in zip(
        EXPECTED_TRACE_TREES, fake_backend.trace_trees
    ):
        assert_equal(expected_trace, actual_trace)


def test_evaluate_prompt__with_random_sampling__happy_flow(
    fake_backend,
):
    # Creates a dataset with 5 items and then evaluates it using a random dataset sampler with 3 samples limit.
    # Checks that only three samples are selected and that the metrics are computed for the three samples.
    MODEL_NAME = "gpt-3.5-turbo"

    mock_dataset = mock.MagicMock(
        spec=["__internal_api__get_items_as_dataclasses__", "id"]
    )
    mock_dataset.name = "the-dataset-name"
    mock_dataset.__internal_api__get_items_as_dataclasses__.return_value = [
        dataset_item.DatasetItem(
            id="dataset-item-id-1",
            question="Hello, world!",
            reference="Hello, world!",
        ),
        dataset_item.DatasetItem(
            id="dataset-item-id-2",
            question="What is the capital of France?",
            reference="Paris",
        ),
        dataset_item.DatasetItem(
            id="dataset-item-id-3",
            question="Say hello",
            reference="Hello, world!",
        ),
        dataset_item.DatasetItem(
            id="dataset-item-id-4",
            question="How are you!",
            reference="Hello, world!",
        ),
        dataset_item.DatasetItem(
            id="dataset-item-id-5",
            question="What time is it?",
            reference="Tea time!",
        ),
    ]

    mock_experiment = mock.Mock()
    mock_create_experiment = mock.Mock()
    mock_create_experiment.return_value = mock_experiment

    mock_get_experiment_url_by_id = mock.Mock()
    mock_get_experiment_url_by_id.return_value = "any_url"

    mock_models_factory_get = mock.Mock()
    mock_model = mock.Mock()
    mock_model.model_name = MODEL_NAME
    mock_model.generate_provider_response.return_value = mock.Mock(
        choices=[mock.Mock(message=mock.Mock(content="Hello, world!"))]
    )
    mock_models_factory_get.return_value = mock_model

    # create a random sampler with 3 samples limit
    sampler = samplers.RandomDatasetSampler(max_samples=3)

    with mock.patch.object(
        opik_client.Opik, "create_experiment", mock_create_experiment
    ):
        with mock.patch.object(
            url_helpers, "get_experiment_url_by_id", mock_get_experiment_url_by_id
        ):
            with mock.patch.object(
                models_factory,
                "get",
                mock_models_factory_get,
            ):
                evaluation.evaluate_prompt(
                    dataset=mock_dataset,
                    messages=[
                        {"role": "user", "content": "LLM response: {{input}}"},
                    ],
                    experiment_name="the-experiment-name",
                    model=MODEL_NAME,
                    scoring_metrics=[metrics.Equals()],
                    task_threads=1,
                    dataset_sampler=sampler,
                )

    mock_dataset.__internal_api__get_items_as_dataclasses__.assert_called_once()

    mock_create_experiment.assert_called_once_with(
        dataset_name="the-dataset-name",
        name="the-experiment-name",
        experiment_config={
            "prompt_template": [{"role": "user", "content": "LLM response: {{input}}"}],
            "model": "gpt-3.5-turbo",
        },
        prompts=None,
    )

    mock_experiment.insert.assert_has_calls(
        [
            mock.call(experiment_items_references=mock.ANY),
            mock.call(experiment_items_references=mock.ANY),
        ]
    )

    # Due to the random nature of the sampler, we need to verify the structure
    # and that exactly 3 samples were selected, but not specific dataset items
    actual_traces = fake_backend.trace_trees
    assert len(actual_traces) == 3, f"Expected 3 traces, got {len(actual_traces)}"

    # Verify each trace has the expected structure for prompt evaluation
    # Since the mock LLM always returns "Hello, world!", the test verifies:
    #     - Score = 1.0 when reference = "Hello, world!"
    #     - Score = 0.0 when reference = anything else
    for actual_trace in actual_traces:
        # Verify feedback scores
        assert len(actual_trace.feedback_scores) == 1
        feedback_score = actual_trace.feedback_scores[0]
        assert feedback_score.name == "equals_metric"
        assert feedback_score.value in [0.0, 1.0]  # Should be either 0 or 1

        # Verify scoring logic - LLM always outputs "Hello, world!"
        reference = actual_trace.input["reference"]
        expected_score = 1.0 if reference == "Hello, world!" else 0.0
        assert feedback_score.value == expected_score


def test_evaluate__2_trials_lead_to_2_experiment_items_per_dataset_item(
    fake_backend,
):
    mock_dataset = mock.MagicMock(
        spec=["__internal_api__get_items_as_dataclasses__", "id"]
    )
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

    mock_get_experiment_url_by_id = mock.Mock()
    mock_get_experiment_url_by_id.return_value = "any_url"

    with mock.patch.object(
        opik_client.Opik, "create_experiment", mock_create_experiment
    ):
        with mock.patch.object(
            url_helpers, "get_experiment_url_by_id", mock_get_experiment_url_by_id
        ):
            evaluation.evaluate(
                dataset=mock_dataset,
                task=say_task,
                experiment_name="the-experiment-name",
                scoring_metrics=[metrics.Equals()],
                task_threads=1,
                trial_count=2,
            )

    mock_dataset.__internal_api__get_items_as_dataclasses__.assert_called_once()

    mock_create_experiment.assert_called_once_with(
        dataset_name="the-dataset-name",
        name="the-experiment-name",
        experiment_config=None,
        prompts=None,
    )

    # With 2 trials and 2 dataset items, we expect 4 calls to insert
    mock_experiment.insert.assert_has_calls(
        [
            mock.call(experiment_items_references=mock.ANY),
            mock.call(experiment_items_references=mock.ANY),
            mock.call(experiment_items_references=mock.ANY),
            mock.call(experiment_items_references=mock.ANY),
        ]
    )

    # With 2 trials and 2 dataset items, we should have 4 trace trees total
    assert len(fake_backend.trace_trees) == 4

    # Check that we have 2 traces for each dataset item
    dataset_item_1_traces = [
        trace
        for trace in fake_backend.trace_trees
        if trace.input["id"] == "dataset-item-id-1"
    ]
    dataset_item_2_traces = [
        trace
        for trace in fake_backend.trace_trees
        if trace.input["id"] == "dataset-item-id-2"
    ]

    assert len(dataset_item_1_traces) == 2
    assert len(dataset_item_2_traces) == 2

    # Define expected trace models
    EXPECTED_TRACE_DATASET_ITEM_1 = TraceModel(
        id=ANY_BUT_NONE,
        name="evaluation_task",
        input={
            "input": {"message": "say hello"},
            "reference": "hello",
            "id": "dataset-item-id-1",
        },
        output={"output": "hello"},
        start_time=ANY_BUT_NONE,
        end_time=ANY_BUT_NONE,
        last_updated_at=ANY_BUT_NONE,
        feedback_scores=[
            FeedbackScoreModel(
                id=ANY_BUT_NONE,
                name="equals_metric",
                value=1.0,
            )
        ],
        spans=ANY_BUT_NONE,  # We don't need to verify span details for this test
    )

    EXPECTED_TRACE_DATASET_ITEM_2 = TraceModel(
        id=ANY_BUT_NONE,
        name="evaluation_task",
        input={
            "input": {"message": "say bye"},
            "reference": "bye",
            "id": "dataset-item-id-2",
        },
        output={"output": "not bye"},
        start_time=ANY_BUT_NONE,
        end_time=ANY_BUT_NONE,
        last_updated_at=ANY_BUT_NONE,
        feedback_scores=[
            FeedbackScoreModel(
                id=ANY_BUT_NONE,
                name="equals_metric",
                value=0.0,
            )
        ],
        spans=ANY_BUT_NONE,  # We don't need to verify span details for this test
    )

    # Verify each trace matches the expected model
    for trace in dataset_item_1_traces:
        assert_equal(EXPECTED_TRACE_DATASET_ITEM_1, trace)

    for trace in dataset_item_2_traces:
        assert_equal(EXPECTED_TRACE_DATASET_ITEM_2, trace)


def test_evaluate_prompt__2_trials_lead_to_2_experiment_items_per_dataset_item(
    fake_backend,
):
    mock_dataset = mock.MagicMock(
        spec=["__internal_api__get_items_as_dataclasses__", "id"]
    )
    mock_dataset.name = "the-dataset-name"
    mock_dataset.__internal_api__get_items_as_dataclasses__.return_value = [
        dataset_item.DatasetItem(
            id="dataset-item-id-1",
            question="Hello, world!",
            reference="Hello, world!",
        ),
        dataset_item.DatasetItem(
            id="dataset-item-id-2",
            question="What is the capital of France?",
            reference="Paris",
        ),
    ]

    mock_experiment = mock.Mock()
    mock_create_experiment = mock.Mock()
    mock_create_experiment.return_value = mock_experiment

    mock_get_experiment_url_by_id = mock.Mock()
    mock_get_experiment_url_by_id.return_value = "any_url"

    mock_models_factory_get = mock.Mock()
    mock_model = mock.Mock()
    mock_model.model_name = "some-model-name"
    mock_model.generate_provider_response.return_value = mock.Mock(
        choices=[mock.Mock(message=mock.Mock(content="Hello, world!"))]
    )
    mock_models_factory_get.return_value = mock_model

    with mock.patch.object(
        opik_client.Opik, "create_experiment", mock_create_experiment
    ):
        with mock.patch.object(
            url_helpers, "get_experiment_url_by_id", mock_get_experiment_url_by_id
        ):
            with mock.patch.object(
                models_factory,
                "get",
                mock_models_factory_get,
            ):
                evaluation.evaluate_prompt(
                    dataset=mock_dataset,
                    messages=[
                        {"role": "user", "content": "LLM response: {{input}}"},
                    ],
                    experiment_name="the-experiment-name",
                    model="some-model-name",
                    scoring_metrics=[metrics.Equals()],
                    task_threads=1,
                    trial_count=2,
                )

    mock_dataset.__internal_api__get_items_as_dataclasses__.assert_called_once()

    mock_create_experiment.assert_called_once_with(
        dataset_name="the-dataset-name",
        name="the-experiment-name",
        experiment_config={
            "prompt_template": [{"role": "user", "content": "LLM response: {{input}}"}],
            "model": "some-model-name",
        },
        prompts=None,
    )

    # With 2 trials and 2 dataset items, we expect 4 calls to insert
    mock_experiment.insert.assert_has_calls(
        [
            mock.call(experiment_items_references=mock.ANY),
            mock.call(experiment_items_references=mock.ANY),
            mock.call(experiment_items_references=mock.ANY),
            mock.call(experiment_items_references=mock.ANY),
        ]
    )

    # With 2 trials and 2 dataset items, we should have 4 trace trees total
    assert len(fake_backend.trace_trees) == 4

    # Check that we have 2 traces for each dataset item
    dataset_item_1_traces = [
        trace
        for trace in fake_backend.trace_trees
        if trace.input["id"] == "dataset-item-id-1"
    ]
    dataset_item_2_traces = [
        trace
        for trace in fake_backend.trace_trees
        if trace.input["id"] == "dataset-item-id-2"
    ]

    assert len(dataset_item_1_traces) == 2
    assert len(dataset_item_2_traces) == 2

    # Define expected trace models
    EXPECTED_TRACE_DATASET_ITEM_1 = TraceModel(
        id=ANY_BUT_NONE,
        name="evaluation_task",
        input={
            "question": "Hello, world!",
            "reference": "Hello, world!",
            "id": "dataset-item-id-1",
        },
        output={
            "input": [{"role": "user", "content": "LLM response: {{input}}"}],
            "output": "Hello, world!",
        },
        start_time=ANY_BUT_NONE,
        end_time=ANY_BUT_NONE,
        last_updated_at=ANY_BUT_NONE,
        feedback_scores=[
            FeedbackScoreModel(
                id=ANY_BUT_NONE,
                name="equals_metric",
                value=1.0,
            )
        ],
        spans=ANY_LIST,  # We don't need to verify span details for this test
    )

    EXPECTED_TRACE_DATASET_ITEM_2 = TraceModel(
        id=ANY_BUT_NONE,
        name="evaluation_task",
        input={
            "question": "What is the capital of France?",
            "reference": "Paris",
            "id": "dataset-item-id-2",
        },
        output={
            "input": [{"role": "user", "content": "LLM response: {{input}}"}],
            "output": "Hello, world!",
        },
        start_time=ANY_BUT_NONE,
        end_time=ANY_BUT_NONE,
        last_updated_at=ANY_BUT_NONE,
        feedback_scores=[
            FeedbackScoreModel(
                id=ANY_BUT_NONE,
                name="equals_metric",
                value=0.0,
            )
        ],
        spans=ANY_LIST,  # We don't need to verify span details for this test
    )

    for trace in dataset_item_1_traces:
        assert_equal(EXPECTED_TRACE_DATASET_ITEM_1, trace)

    for trace in dataset_item_2_traces:
        assert_equal(EXPECTED_TRACE_DATASET_ITEM_2, trace)


def test_evaluate_on_dict_items__happyflow(fake_backend):
    items = [
        {"input": "What is 2+2?", "expected_output": "4"},
        {"input": "What is 3+3?", "expected_output": "6"},
    ]

    def simple_task(item):
        # Simple echo task for testing
        if "2+2" in item["input"]:
            return {"output": "4"}
        return {"output": "6"}

    result = evaluation.evaluator.evaluate_on_dict_items(
        items=items,
        task=simple_task,
        scoring_metrics=[metrics.Equals()],
        scoring_key_mapping={"reference": "expected_output"},
        scoring_threads=1,  # Use single thread for deterministic order
    )

    assert len(result.test_results) == 2

    # Check first result
    assert result.test_results[0].test_case.task_output == {"output": "4"}
    assert result.test_results[0].score_results[0].value == 1.0
    assert result.test_results[0].score_results[0].name == "equals_metric"

    # Check second result
    assert result.test_results[1].test_case.task_output == {"output": "6"}
    assert result.test_results[1].score_results[0].value == 1.0
    assert result.test_results[1].score_results[0].name == "equals_metric"
    

    # Test aggregation
    aggregated = result.aggregate_evaluation_scores()
    assert aggregated == {
        "equals_metric": score_statistics.ScoreStatistics(
            mean=1.0,
            max=1.0,
            min=1.0,
            values=[1.0, 1.0],
            std=0.0,
        )
    }


def test_evaluate_on_dict_items__with_scoring_key_mapping(fake_backend):
    items = [
        {"user_question": "Hello?", "expected_answer": "Hi"},
    ]

    def task(item):
        return {"model_response": "Hi"}

    result = evaluation.evaluate_on_dict_items(
        items=items,
        task=task,
        scoring_metrics=[metrics.Equals()],
        scoring_key_mapping={
            "input": "user_question",
            "output": "model_response",
            "reference": "expected_answer",
        },
        scoring_threads=1,
    )

    assert len(result.test_results) == 1
    assert result.test_results[0].score_results[0].value == 1.0


def test_evaluate_on_dict_items__multiple_metrics(fake_backend):
    items = [
        {"input": "test", "expected_output": "test"},
    ]

    def task(item):
        return {"output": "test"}

    class CustomMetric(metrics.base_metric.BaseMetric):
        def score(self, output: str, **kwargs):
            return score_result.ScoreResult(
                name="custom_metric",
                value=0.5,
            )

    result = evaluation.evaluator.evaluate_on_dict_items(
        items=items,
        task=task,
        scoring_metrics=[metrics.Equals(), CustomMetric()],
        scoring_key_mapping={"reference": "expected_output"},
        scoring_threads=1,
    )

    assert len(result.test_results) == 1
    assert len(result.test_results[0].score_results) == 2
    assert result.test_results[0].score_results[0] == score_result.ScoreResult(
        name="equals_metric",
        value=1.0,
    )
    assert result.test_results[0].score_results[1] == score_result.ScoreResult(
        name="custom_metric",
        value=0.5,
    )

    # Test aggregation with multiple metrics
    aggregated = result.aggregate_evaluation_scores()
    assert aggregated == {
        "equals_metric": score_statistics.ScoreStatistics(
            mean=1.0,
            max=1.0,
            min=1.0,
            values=[1.0],
            std=None,
        ),
        "custom_metric": score_statistics.ScoreStatistics(
            mean=0.5,
            max=0.5,
            min=0.5,
            values=[0.5],
            std=None,
        ),
    }


def test_evaluate_on_dict_items__task_execution(fake_backend):
    items = [{"value": 5, "expected": 10}]

    task_calls = []

    def task(item):
        task_calls.append(item)
        return {"result": item["value"] * 2}

    class CustomMetric(metrics.base_metric.BaseMetric):
        def score(self, output: int, reference: int, **kwargs):
            return score_result.ScoreResult(
                name="result_check",
                value=1.0 if output == reference else 0.0,
            )

    result = evaluation.evaluator.evaluate_on_dict_items(
        items=items,
        task=task,
        scoring_metrics=[CustomMetric()],
        scoring_key_mapping={"output": "result", "reference": "expected"},
        scoring_threads=1,
    )

    # Verify task was called with correct input
    assert task_calls == [{"value": 5, "expected": 10, "id": "temp_item_0"}]

    # Verify result
    assert result.test_results[0].test_case.task_output == {"result": 10}
    assert result.test_results[0].score_results[0].value == 1.0


def test_evaluate_on_dict_items__no_metrics_returns_empty(fake_backend):
    items = [{"input": "test"}]

    def task(item):
        return {"output": "test"}

    result = evaluation.evaluate_on_dict_items(
        items=items,
        task=task,
        scoring_metrics=[],
        scoring_threads=1,
    )

    assert result.test_results == []


def test_evaluate_on_dict_items__empty_items_list(fake_backend):
    """Test that empty items list returns empty results."""
    items = []

    def task(item):
        return {"output": "test"}

    result = evaluation.evaluate_on_dict_items(
        items=items,
        task=task,
        scoring_metrics=[metrics.Equals()],
        scoring_threads=1,
    )

    assert result.test_results == []


def test_evaluate_on_dict_items__task_raises_exception(fake_backend):
    """Test that exceptions in task execution are properly propagated."""
    items = [{"input": "test", "expected": "result"}]

    def failing_task(item):
        raise ValueError("Task failed")

    with pytest.raises(ValueError, match="Task failed"):
        evaluation.evaluate_on_dict_items(
            items=items,
            task=failing_task,
            scoring_metrics=[metrics.Equals()],
            scoring_key_mapping={"reference": "expected"},
            scoring_threads=1,
        )


def test_evaluate_on_dict_items__with_scoring_functions(fake_backend):
    """Test evaluate_on_dict_items with scoring functions instead of metrics."""
    items = [
        {"input": "What is 2+2?", "expected_output": "4"},
        {"input": "What is 3+3?", "expected_output": "6"},
    ]

    def task(item: Dict[str, Any]) -> Dict[str, Any]:
        if "2+2" in item["input"]:
            return {"output": "4"}
        return {"output": "6"}

    def custom_scorer(
        dataset_item: Dict[str, Any],
        task_outputs: Dict[str, Any],
    ) -> score_result.ScoreResult:
        expected = dataset_item.get("expected_output", "")
        actual = task_outputs.get("output", "")
        return score_result.ScoreResult(
            name="custom_scorer",
            value=1.0 if expected == actual else 0.0,
            reason=f"Expected: {expected}, Got: {actual}",
        )

    result = evaluation.evaluate_on_dict_items(
        items=items,
        task=task,
        scoring_functions=[custom_scorer],
        scoring_threads=1,
    )

    # Verify results structure
    assert len(result.test_results) == 2

    # Verify scoring results
    assert result.test_results[0].score_results[0] == score_result.ScoreResult(
        name="custom_scorer",
        value=1.0,
        reason="Expected: 4, Got: 4",
    )
    assert result.test_results[1].score_results[0] == score_result.ScoreResult(
        name="custom_scorer",
        value=1.0,
        reason="Expected: 6, Got: 6",
    )

    # Verify aggregation
    aggregated = result.aggregate_evaluation_scores()
    assert aggregated == {
        "custom_scorer": score_statistics.ScoreStatistics(
            mean=1.0,
            max=1.0,
            min=1.0,
            values=[1.0, 1.0],
            std=0.0,
        )
    }
