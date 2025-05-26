from typing import Any, Dict, List

from unittest import mock
import pytest

import opik
from opik import evaluation, exceptions, url_helpers
from opik.api_objects import opik_client
from opik.api_objects.dataset import dataset_item
from opik.evaluation import metrics
from opik.evaluation.metrics import score_result
from opik.evaluation.models import models_factory
from ...testlib import ANY_BUT_NONE, ANY_STRING, SpanModel, assert_equal
from ...testlib.models import FeedbackScoreModel, TraceModel


def test_evaluate__happyflow(
    fake_backend,
    configure_opik_local_env_vars,
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
            },
            output={
                "output": "hello",
            },
            start_time=ANY_BUT_NONE,
            end_time=ANY_BUT_NONE,
            spans=[
                SpanModel(
                    id=ANY_BUT_NONE,
                    type="general",
                    name="say_task",
                    input={
                        "dataset_item": {
                            "input": {"message": "say hello"},
                            "reference": "hello",
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
                                "ignored_kwargs": {"input": {"message": "say hello"}},
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
            },
            output={
                "output": "not bye",
            },
            start_time=ANY_BUT_NONE,
            end_time=ANY_BUT_NONE,
            spans=[
                SpanModel(
                    id=ANY_BUT_NONE,
                    type="general",
                    name="say_task",
                    input={
                        "dataset_item": {
                            "input": {"message": "say bye"},
                            "reference": "bye",
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
                                "ignored_kwargs": {"input": {"message": "say bye"}},
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
    configure_opik_local_env_vars,
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
            },
            output={
                "result": "hello",
            },
            start_time=ANY_BUT_NONE,
            end_time=ANY_BUT_NONE,
            spans=[
                SpanModel(
                    id=ANY_BUT_NONE,
                    type="general",
                    name="say_task",
                    input={
                        "dataset_item": {
                            "input": {"message": "say hello"},
                            "expected_output": {"message": "hello"},
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
            },
            output={
                "result": "not bye",
            },
            start_time=ANY_BUT_NONE,
            end_time=ANY_BUT_NONE,
            spans=[
                SpanModel(
                    id=ANY_BUT_NONE,
                    type="general",
                    name="say_task",
                    input={
                        "dataset_item": {
                            "input": {"message": "say bye"},
                            "expected_output": {"message": "bye"},
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


def test_evaluate___output_key_is_missing_in_task_output_dict__equals_metric_misses_output_argument__exception_raised(
    configure_opik_local_env_vars,
):
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
    configure_opik_local_env_vars,
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
        },
        output=None,
        start_time=ANY_BUT_NONE,
        end_time=ANY_BUT_NONE,
        error_info={
            "exception_type": "Exception",
            "message": "some-error-message",
            "traceback": ANY_STRING(),
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
                    }
                },
                error_info={
                    "exception_type": "Exception",
                    "message": "some-error-message",
                    "traceback": ANY_STRING(),
                },
                output=None,
                start_time=ANY_BUT_NONE,
                end_time=ANY_BUT_NONE,
                spans=[],
            ),
        ],
    )

    assert_equal(EXPECTED_TRACE_TREE, fake_backend.trace_trees[0])


def test_evaluate_prompt_happyflow(
    fake_backend,
    configure_opik_local_env_vars,
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
            },
            output={
                "input": [{"role": "user", "content": "LLM response: {{input}}"}],
                "output": "Hello, world!",
            },
            start_time=ANY_BUT_NONE,
            end_time=ANY_BUT_NONE,
            spans=[
                SpanModel(
                    id=ANY_BUT_NONE,
                    type="general",
                    name="_prompt_evaluation_task",
                    input={
                        "prompt_variables": {
                            "question": "Hello, world!",
                            "reference": "Hello, world!",
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
            },
            output={
                "input": [{"role": "user", "content": "LLM response: {{input}}"}],
                "output": "Hello, world!",
            },
            start_time=ANY_BUT_NONE,
            end_time=ANY_BUT_NONE,
            spans=[
                SpanModel(
                    id=ANY_BUT_NONE,
                    type="general",
                    name="_prompt_evaluation_task",
                    input={
                        "prompt_variables": {
                            "question": "What is the capital of France?",
                            "reference": "Paris",
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
    configure_opik_local_env_vars,
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
            },
            output={
                "output": "hello",
            },
            start_time=ANY_BUT_NONE,
            end_time=ANY_BUT_NONE,
            spans=[
                SpanModel(
                    id=ANY_BUT_NONE,
                    type="general",
                    name="say_task",
                    input={
                        "dataset_item": {
                            "input": {"message": "say hello"},
                            "reference": "hello",
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
                                            "input": {"message": "say hello"}
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
                                            "input": {"message": "say hello"}
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
            },
            output={
                "output": "not bye",
            },
            start_time=ANY_BUT_NONE,
            end_time=ANY_BUT_NONE,
            spans=[
                SpanModel(
                    id=ANY_BUT_NONE,
                    type="general",
                    name="say_task",
                    input={
                        "dataset_item": {
                            "input": {"message": "say bye"},
                            "reference": "bye",
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
                                            "input": {"message": "say bye"}
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
                                            "input": {"message": "say bye"}
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
