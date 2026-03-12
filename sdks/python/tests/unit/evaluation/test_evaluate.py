from contextlib import contextmanager
from typing import Any, Dict, List
from unittest import mock
import pytest

import opik
from opik import evaluation, exceptions, url_helpers
from opik.api_objects import opik_client
from opik.api_objects.dataset import dataset_item
from opik.api_objects.experiment import experiment
from opik.evaluation import (
    evaluator as evaluator_module,
    metrics,
    samplers,
    score_statistics,
)
from opik.evaluation.engine import engine
from opik.evaluation.metrics import score_result
from opik.evaluation.models import models_factory
from opik.evaluation.evaluator import _build_prompt_evaluation_task

from ...testlib import ANY_BUT_NONE, ANY_STRING, ANY_LIST, SpanModel, assert_equal
from ...testlib.models import FeedbackScoreModel, TraceModel


def create_mock_dataset(
    name: str = "the-dataset-name",
    items: List[dataset_item.DatasetItem] = None,
) -> mock.MagicMock:
    """Create a mock dataset with streaming support."""
    mock_dataset = mock.MagicMock(
        spec=[
            "__internal_api__stream_items_as_dataclasses__",
            "id",
            "dataset_items_count",
            "get_version_info",
        ]
    )
    mock_dataset.name = name
    mock_dataset.dataset_items_count = None
    mock_dataset.get_version_info.return_value = None
    if items is not None:
        mock_dataset.__internal_api__stream_items_as_dataclasses__.return_value = iter(
            items
        )
    return mock_dataset


def create_mock_experiment() -> tuple[mock.Mock, mock.Mock, mock.Mock]:
    """Create mock experiment and related mocks for patching.

    Returns:
        Tuple of (mock_experiment, mock_create_experiment, mock_get_experiment_url_by_id)
    """
    mock_experiment = mock.Mock()
    mock_create_experiment = mock.Mock()
    mock_create_experiment.return_value = mock_experiment

    mock_get_experiment_url_by_id = mock.Mock()
    mock_get_experiment_url_by_id.return_value = "any_url"

    return mock_experiment, mock_create_experiment, mock_get_experiment_url_by_id


def create_mock_model(
    model_name: str = "gpt-3.5-turbo",
    response_content: str = "Hello, world!",
) -> tuple[mock.Mock, mock.Mock]:
    """Create mock model and factory for evaluate_prompt tests.

    Returns:
        Tuple of (mock_models_factory_get, mock_model)
    """
    mock_models_factory_get = mock.Mock()
    mock_model = mock.Mock()
    mock_model.model_name = model_name
    mock_model.generate_provider_response.return_value = mock.Mock(
        choices=[mock.Mock(message=mock.Mock(content=response_content))]
    )
    mock_models_factory_get.return_value = mock_model

    return mock_models_factory_get, mock_model


@contextmanager
def patch_evaluation_dependencies(
    mock_create_experiment: mock.Mock,
    mock_get_experiment_url_by_id: mock.Mock,
    mock_models_factory_get: mock.Mock = None,
):
    """Context manager to patch evaluation dependencies.

    Args:
        mock_create_experiment: Mock for opik_client.Opik.create_experiment
        mock_get_experiment_url_by_id: Mock for url_helpers.get_experiment_url_by_id
        mock_models_factory_get: Optional mock for models_factory.get (for evaluate_prompt tests)
    """
    with mock.patch.object(
        opik_client.Opik, "create_experiment", mock_create_experiment
    ):
        with mock.patch.object(
            url_helpers, "get_experiment_url_by_id", mock_get_experiment_url_by_id
        ):
            if mock_models_factory_get is not None:
                with mock.patch.object(
                    models_factory,
                    "get",
                    mock_models_factory_get,
                ):
                    yield
            else:
                yield


def test_evaluate__happyflow(
    fake_backend,
):
    mock_dataset = mock.MagicMock(
        spec=[
            "__internal_api__stream_items_as_dataclasses__",
            "id",
            "dataset_items_count",
            "get_version_info",
            "get_execution_policy",
            "get_evaluators",
        ]
    )
    mock_dataset.name = "the-dataset-name"
    mock_dataset.dataset_items_count = None
    mock_dataset.get_version_info.return_value = None
    mock_dataset.get_execution_policy.return_value = {
        "runs_per_item": 1,
        "pass_threshold": 1,
    }
    mock_dataset.get_evaluators.return_value = []
    mock_dataset.__internal_api__stream_items_as_dataclasses__.return_value = iter(
        [
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
    )

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

    experiment_tags = ["one", "two", "three"]

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
                experiment_tags=experiment_tags,
            )

    mock_dataset.__internal_api__stream_items_as_dataclasses__.assert_called_once()

    mock_create_experiment.assert_called_once_with(
        dataset_name="the-dataset-name",
        name="the-experiment-name",
        experiment_config=None,
        prompts=None,
        tags=experiment_tags,
        dataset_version_id=None,
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
        spec=[
            "__internal_api__stream_items_as_dataclasses__",
            "id",
            "dataset_items_count",
            "get_version_info",
            "get_execution_policy",
            "get_evaluators",
        ]
    )
    mock_dataset.name = "the-dataset-name"
    mock_dataset.dataset_items_count = None
    mock_dataset.get_version_info.return_value = None
    mock_dataset.get_execution_policy.return_value = {
        "runs_per_item": 1,
        "pass_threshold": 1,
    }
    mock_dataset.get_evaluators.return_value = []
    mock_dataset.__internal_api__stream_items_as_dataclasses__.return_value = iter(
        [
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
    )

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

    mock_dataset.__internal_api__stream_items_as_dataclasses__.assert_called_once()

    mock_create_experiment.assert_called_once_with(
        dataset_name="the-dataset-name",
        name="the-experiment-name",
        experiment_config=None,
        prompts=None,
        tags=None,
        dataset_version_id=None,
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
        spec=[
            "__internal_api__stream_items_as_dataclasses__",
            "id",
            "dataset_items_count",
            "get_version_info",
            "get_execution_policy",
            "get_evaluators",
        ]
    )
    mock_dataset.name = "the-dataset-name"
    mock_dataset.dataset_items_count = None
    mock_dataset.get_version_info.return_value = None
    mock_dataset.get_execution_policy.return_value = {
        "runs_per_item": 1,
        "pass_threshold": 1,
    }
    mock_dataset.get_evaluators.return_value = []
    mock_dataset.__internal_api__stream_items_as_dataclasses__.return_value = iter(
        [
            dataset_item.DatasetItem(
                id="dataset-item-id-1",
                input={"message": "say hello"},
                expected_output={"message": "hello"},
            ),
        ]
    )

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

    mock_dataset.__internal_api__stream_items_as_dataclasses__.assert_called_once()


def test_evaluate__exception_raised_from_the_task__error_info_added_to_the_trace(
    fake_backend,
):
    mock_dataset = mock.MagicMock(
        spec=[
            "__internal_api__stream_items_as_dataclasses__",
            "id",
            "dataset_items_count",
            "get_version_info",
            "get_execution_policy",
            "get_evaluators",
        ]
    )
    mock_dataset.name = "the-dataset-name"
    mock_dataset.dataset_items_count = None
    mock_dataset.get_version_info.return_value = None
    mock_dataset.get_execution_policy.return_value = {
        "runs_per_item": 1,
        "pass_threshold": 1,
    }
    mock_dataset.get_evaluators.return_value = []
    mock_dataset.__internal_api__stream_items_as_dataclasses__.return_value = iter(
        [
            dataset_item.DatasetItem(
                id="dataset-item-id-1",
                input={"message": "say hello"},
                reference="hello",
            ),
        ]
    )

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

    mock_dataset.__internal_api__stream_items_as_dataclasses__.assert_called_once()

    mock_create_experiment.assert_called_once_with(
        dataset_name="the-dataset-name",
        name="the-experiment-name",
        experiment_config=None,
        prompts=None,
        tags=None,
        dataset_version_id=None,
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
        spec=[
            "__internal_api__stream_items_as_dataclasses__",
            "id",
            "dataset_items_count",
            "get_version_info",
            "get_execution_policy",
            "get_evaluators",
        ]
    )
    mock_dataset.name = "the-dataset-name"
    mock_dataset.dataset_items_count = None
    mock_dataset.get_version_info.return_value = None
    mock_dataset.get_execution_policy.return_value = {
        "runs_per_item": 1,
        "pass_threshold": 1,
    }
    mock_dataset.get_evaluators.return_value = []
    # When dataset_sampler is provided, streaming is used but exhausted to a list
    mock_dataset.__internal_api__stream_items_as_dataclasses__.return_value = iter(
        [
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
    )

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

    # When dataset_sampler is provided, streaming is still used but exhausted to a list
    mock_dataset.__internal_api__stream_items_as_dataclasses__.assert_called_once()

    mock_create_experiment.assert_called_once_with(
        dataset_name="the-dataset-name",
        name="the-experiment-name",
        experiment_config=None,
        prompts=None,
        tags=None,
        dataset_version_id=None,
    )

    mock_experiment.insert.assert_has_calls(
        [
            mock.call(experiment_items_references=mock.ANY),
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


def test_evaluate__with_random_sampler__total_items_reflects_sampled_count(
    fake_backend,
):
    """Test that total_items passed to executor reflects the sampled count, not the original dataset size."""
    mock_dataset = mock.MagicMock(
        spec=[
            "__internal_api__stream_items_as_dataclasses__",
            "id",
            "dataset_items_count",
            "get_version_info",
            "get_execution_policy",
            "get_evaluators",
        ]
    )
    mock_dataset.name = "the-dataset-name"
    mock_dataset.dataset_items_count = 10  # Original dataset has 10 items
    mock_dataset.get_version_info.return_value = None
    mock_dataset.get_execution_policy.return_value = {
        "runs_per_item": 1,
        "pass_threshold": 1,
    }
    mock_dataset.get_evaluators.return_value = []
    # Return 10 items
    mock_dataset.__internal_api__stream_items_as_dataclasses__.return_value = iter(
        [
            dataset_item.DatasetItem(
                id=f"dataset-item-id-{i}",
                input={"message": f"message {i}"},
                reference="hello",
            )
            for i in range(10)
        ]
    )

    def say_task(dataset_item: Dict[str, Any]):
        return {"output": "hello"}

    mock_experiment = mock.Mock()
    mock_create_experiment = mock.Mock()
    mock_create_experiment.return_value = mock_experiment

    mock_get_experiment_url_by_id = mock.Mock()
    mock_get_experiment_url_by_id.return_value = "any_url"

    # Create a sampler that will reduce to 3 items
    sampler = samplers.RandomDatasetSampler(max_samples=3)

    # Patch the engine's _compute_test_results_with_execution_policy to capture total_items
    captured_total_items = []

    original_compute = (
        engine.EvaluationEngine._compute_test_results_with_execution_policy
    )

    def patched_compute(self, *args, **kwargs):
        captured_total_items.append(kwargs.get("total_items"))
        return original_compute(self, *args, **kwargs)

    with mock.patch.object(
        opik_client.Opik, "create_experiment", mock_create_experiment
    ):
        with mock.patch.object(
            url_helpers, "get_experiment_url_by_id", mock_get_experiment_url_by_id
        ):
            with mock.patch.object(
                engine.EvaluationEngine,
                "_compute_test_results_with_execution_policy",
                patched_compute,
            ):
                evaluation.evaluate(
                    dataset=mock_dataset,
                    task=say_task,
                    experiment_name="the-experiment-name",
                    scoring_metrics=[metrics.Equals()],
                    task_threads=1,
                    dataset_sampler=sampler,
                )

    # Verify that total_items was 3 (sampled count), not 10 (original dataset size)
    assert len(captured_total_items) == 1
    assert captured_total_items[0] == 3, (
        f"Expected total_items to be 3 (sampled count), "
        f"but got {captured_total_items[0]} (original dataset size)"
    )

    # Also verify that only 3 items were actually processed
    actual_traces = fake_backend.trace_trees
    assert len(actual_traces) == 3, f"Expected 3 traces, got {len(actual_traces)}"


def test_evaluate__with_task_span_metrics__total_items_reflects_actual_count(
    fake_backend,
):
    """Test that total_items is correct when task_span_metrics forces non-streaming mode."""
    mock_dataset = mock.MagicMock(
        spec=[
            "__internal_api__stream_items_as_dataclasses__",
            "id",
            "dataset_items_count",
            "get_version_info",
            "get_execution_policy",
            "get_evaluators",
        ]
    )
    mock_dataset.name = "the-dataset-name"
    mock_dataset.dataset_items_count = 5
    mock_dataset.get_version_info.return_value = None
    mock_dataset.get_execution_policy.return_value = {
        "runs_per_item": 1,
        "pass_threshold": 1,
    }
    mock_dataset.get_evaluators.return_value = []
    # Return 5 items
    mock_dataset.__internal_api__stream_items_as_dataclasses__.return_value = iter(
        [
            dataset_item.DatasetItem(
                id=f"dataset-item-id-{i}",
                input={"message": f"message {i}"},
                reference="hello",
            )
            for i in range(5)
        ]
    )

    def say_task(dataset_item: Dict[str, Any]):
        return {"output": "hello"}

    mock_experiment = mock.Mock()
    mock_create_experiment = mock.Mock()
    mock_create_experiment.return_value = mock_experiment

    mock_get_experiment_url_by_id = mock.Mock()
    mock_get_experiment_url_by_id.return_value = "any_url"

    # Create a task span metric to force non-streaming mode
    class TaskSpanMetric(metrics.base_metric.BaseMetric):
        def score(self, **kwargs):
            return score_result.ScoreResult(name="task_span_metric", value=1.0)

        @property
        def track_task_span(self) -> bool:
            return True

    # Patch the engine's _compute_test_results_for_llm_task to capture total_items
    captured_total_items = []

    original_compute = (
        engine.EvaluationEngine._compute_test_results_with_execution_policy
    )

    def patched_compute(self, *args, **kwargs):
        captured_total_items.append(kwargs.get("total_items"))
        return original_compute(self, *args, **kwargs)

    with mock.patch.object(
        opik_client.Opik, "create_experiment", mock_create_experiment
    ):
        with mock.patch.object(
            url_helpers, "get_experiment_url_by_id", mock_get_experiment_url_by_id
        ):
            with mock.patch.object(
                engine.EvaluationEngine,
                "_compute_test_results_with_execution_policy",
                patched_compute,
            ):
                evaluation.evaluate(
                    dataset=mock_dataset,
                    task=say_task,
                    experiment_name="the-experiment-name",
                    scoring_metrics=[TaskSpanMetric()],
                    task_threads=1,
                )

    # Verify that total_items was 5 (actual count from non-streaming list)
    assert len(captured_total_items) == 1
    assert captured_total_items[0] == 5, (
        f"Expected total_items to be 5 (actual list length), "
        f"but got {captured_total_items[0]}"
    )

    # Also verify that 5 items were actually processed
    actual_traces = fake_backend.trace_trees
    assert len(actual_traces) == 5, f"Expected 5 traces, got {len(actual_traces)}"


def test_evaluate__with_sampler_and_nb_samples__total_items_reflects_final_count(
    fake_backend,
):
    """Test that total_items is correct when both nb_samples and dataset_sampler are used."""
    mock_dataset = mock.MagicMock(
        spec=[
            "__internal_api__stream_items_as_dataclasses__",
            "id",
            "dataset_items_count",
            "get_version_info",
            "get_execution_policy",
            "get_evaluators",
        ]
    )
    mock_dataset.name = "the-dataset-name"
    mock_dataset.dataset_items_count = 100  # Original dataset has 100 items
    mock_dataset.get_version_info.return_value = None
    mock_dataset.get_execution_policy.return_value = {
        "runs_per_item": 1,
        "pass_threshold": 1,
    }
    mock_dataset.get_evaluators.return_value = []
    # nb_samples=10 will fetch 10 items
    mock_dataset.__internal_api__stream_items_as_dataclasses__.return_value = iter(
        [
            dataset_item.DatasetItem(
                id=f"dataset-item-id-{i}",
                input={"message": f"message {i}"},
                reference="hello",
            )
            for i in range(10)  # 10 items fetched due to nb_samples
        ]
    )

    def say_task(dataset_item: Dict[str, Any]):
        return {"output": "hello"}

    mock_experiment = mock.Mock()
    mock_create_experiment = mock.Mock()
    mock_create_experiment.return_value = mock_experiment

    mock_get_experiment_url_by_id = mock.Mock()
    mock_get_experiment_url_by_id.return_value = "any_url"

    # Create a sampler that will further reduce to 3 items
    sampler = samplers.RandomDatasetSampler(max_samples=3)

    # Patch the engine's _compute_test_results_for_llm_task to capture total_items
    captured_total_items = []

    original_compute = (
        engine.EvaluationEngine._compute_test_results_with_execution_policy
    )

    def patched_compute(self, *args, **kwargs):
        captured_total_items.append(kwargs.get("total_items"))
        return original_compute(self, *args, **kwargs)

    with mock.patch.object(
        opik_client.Opik, "create_experiment", mock_create_experiment
    ):
        with mock.patch.object(
            url_helpers, "get_experiment_url_by_id", mock_get_experiment_url_by_id
        ):
            with mock.patch.object(
                engine.EvaluationEngine,
                "_compute_test_results_with_execution_policy",
                patched_compute,
            ):
                evaluation.evaluate(
                    dataset=mock_dataset,
                    task=say_task,
                    experiment_name="the-experiment-name",
                    scoring_metrics=[metrics.Equals()],
                    task_threads=1,
                    nb_samples=10,  # First filter: 10 items
                    dataset_sampler=sampler,  # Second filter: 3 items
                )

    # Verify that total_items was 3 (final sampled count), not 10 (nb_samples) or 100 (dataset size)
    assert len(captured_total_items) == 1
    assert captured_total_items[0] == 3, (
        f"Expected total_items to be 3 (final sampled count), "
        f"but got {captured_total_items[0]}"
    )

    # Verify streaming was called with nb_samples
    mock_dataset.__internal_api__stream_items_as_dataclasses__.assert_called_once_with(
        nb_samples=10,
        dataset_item_ids=None,
        batch_size=evaluator_module.EVALUATION_STREAM_DATASET_BATCH_SIZE,
        filter_string=None,
    )

    # Also verify that only 3 items were actually processed
    actual_traces = fake_backend.trace_trees
    assert len(actual_traces) == 3, f"Expected 3 traces, got {len(actual_traces)}"


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
        spec=[
            "__internal_api__stream_items_as_dataclasses__",
            "id",
            "dataset_items_count",
            "get_version_info",
            "get_execution_policy",
            "get_evaluators",
        ]
    )
    mock_dataset.name = "the-dataset-name"
    mock_dataset.dataset_items_count = None
    mock_dataset.get_version_info.return_value = None
    mock_dataset.get_execution_policy.return_value = {
        "runs_per_item": 1,
        "pass_threshold": 1,
    }
    mock_dataset.get_evaluators.return_value = []
    mock_dataset.__internal_api__stream_items_as_dataclasses__.return_value = iter(
        [
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
    )

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

    experiment_tags = ["one", "two", "three"]

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
                    experiment_tags=experiment_tags,
                )

    mock_dataset.__internal_api__stream_items_as_dataclasses__.assert_called_once()

    mock_create_experiment.assert_called_once_with(
        dataset_name="the-dataset-name",
        name="the-experiment-name",
        experiment_config={
            "prompt_template": [{"role": "user", "content": "LLM response: {{input}}"}],
            "model": "gpt-3.5-turbo",
        },
        prompts=None,
        tags=experiment_tags,
        dataset_version_id=None,
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
        spec=[
            "__internal_api__stream_items_as_dataclasses__",
            "id",
            "dataset_items_count",
            "get_version_info",
            "get_execution_policy",
            "get_evaluators",
        ]
    )
    mock_dataset.name = "the-dataset-name"
    mock_dataset.dataset_items_count = None
    mock_dataset.get_version_info.return_value = None
    mock_dataset.get_execution_policy.return_value = {
        "runs_per_item": 1,
        "pass_threshold": 1,
    }
    mock_dataset.get_evaluators.return_value = []
    mock_dataset.__internal_api__stream_items_as_dataclasses__.return_value = iter(
        [
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
    )

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

    mock_dataset.__internal_api__stream_items_as_dataclasses__.assert_called_once()

    mock_create_experiment.assert_called_once_with(
        dataset_name="the-dataset-name",
        name="the-experiment-name",
        experiment_config=None,
        prompts=None,
        tags=None,
        dataset_version_id=None,
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
        spec=[
            "__internal_api__stream_items_as_dataclasses__",
            "id",
            "dataset_items_count",
            "get_version_info",
            "get_execution_policy",
            "get_evaluators",
        ]
    )
    mock_dataset.name = "the-dataset-name"
    mock_dataset.dataset_items_count = None
    mock_dataset.get_version_info.return_value = None
    mock_dataset.get_execution_policy.return_value = {
        "runs_per_item": 1,
        "pass_threshold": 1,
    }
    mock_dataset.get_evaluators.return_value = []
    # When dataset_sampler is provided, streaming is used but exhausted to a list
    mock_dataset.__internal_api__stream_items_as_dataclasses__.return_value = iter(
        [
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
    )

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

    # When dataset_sampler is provided, streaming is still used but exhausted to a list
    mock_dataset.__internal_api__stream_items_as_dataclasses__.assert_called_once()

    mock_create_experiment.assert_called_once_with(
        dataset_name="the-dataset-name",
        name="the-experiment-name",
        experiment_config={
            "prompt_template": [{"role": "user", "content": "LLM response: {{input}}"}],
            "model": "gpt-3.5-turbo",
        },
        prompts=None,
        tags=None,
        dataset_version_id=None,
    )

    mock_experiment.insert.assert_has_calls(
        [
            mock.call(experiment_items_references=mock.ANY),
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
        spec=[
            "__internal_api__stream_items_as_dataclasses__",
            "id",
            "dataset_items_count",
            "get_version_info",
            "get_execution_policy",
            "get_evaluators",
        ]
    )
    mock_dataset.name = "the-dataset-name"
    mock_dataset.dataset_items_count = None
    mock_dataset.get_version_info.return_value = None
    mock_dataset.get_execution_policy.return_value = {
        "runs_per_item": 2,
        "pass_threshold": 1,
    }
    mock_dataset.get_evaluators.return_value = []
    mock_dataset.__internal_api__stream_items_as_dataclasses__.return_value = iter(
        [
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
    )

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

    mock_dataset.__internal_api__stream_items_as_dataclasses__.assert_called_once()

    mock_create_experiment.assert_called_once_with(
        dataset_name="the-dataset-name",
        name="the-experiment-name",
        experiment_config=None,
        prompts=None,
        tags=None,
        dataset_version_id=None,
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
        spec=[
            "__internal_api__stream_items_as_dataclasses__",
            "id",
            "dataset_items_count",
            "get_version_info",
            "get_execution_policy",
            "get_evaluators",
        ]
    )
    mock_dataset.name = "the-dataset-name"
    mock_dataset.dataset_items_count = None
    mock_dataset.get_version_info.return_value = None
    mock_dataset.get_execution_policy.return_value = {
        "runs_per_item": 2,
        "pass_threshold": 1,
    }
    mock_dataset.get_evaluators.return_value = []
    mock_dataset.__internal_api__stream_items_as_dataclasses__.return_value = iter(
        [
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
    )

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

    mock_dataset.__internal_api__stream_items_as_dataclasses__.assert_called_once()

    mock_create_experiment.assert_called_once_with(
        dataset_name="the-dataset-name",
        name="the-experiment-name",
        experiment_config={
            "prompt_template": [{"role": "user", "content": "LLM response: {{input}}"}],
            "model": "some-model-name",
        },
        prompts=None,
        tags=None,
        dataset_version_id=None,
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


def test_evaluate__with_experiment_scores(fake_backend):
    """Test that experiment_scores are computed and stored correctly."""
    mock_dataset = mock.MagicMock(
        spec=[
            "__internal_api__stream_items_as_dataclasses__",
            "id",
            "name",
            "dataset_items_count",
            "get_version_info",
            "get_execution_policy",
            "get_evaluators",
        ]
    )
    mock_dataset.name = "test-dataset"
    mock_dataset.get_version_info.return_value = None
    mock_dataset.get_execution_policy.return_value = {
        "runs_per_item": 1,
        "pass_threshold": 1,
    }
    mock_dataset.get_evaluators.return_value = []
    mock_dataset.dataset_items_count = None
    mock_dataset.id = "dataset-id"
    mock_dataset.__internal_api__stream_items_as_dataclasses__.return_value = iter(
        [
            dataset_item.DatasetItem(
                id="dataset-item-id-1",
                input={"message": "say hello"},
                reference="hello",
            ),
        ]
    )

    def say_task(dataset_item: Dict[str, Any]):
        return {"output": "hello"}

    # Create a real Experiment instance with mocked dependencies
    mock_rest_client = mock.Mock()
    mock_experiments_api = mock.Mock()
    mock_update_experiment = mock.Mock()
    mock_experiments_api.update_experiment = mock_update_experiment
    mock_rest_client.experiments = mock_experiments_api

    real_experiment = experiment.Experiment(
        id="experiment-id",
        name="test-experiment",
        dataset_name="test-dataset",
        rest_client=mock_rest_client,
        streamer=mock.Mock(),
        experiments_client=mock.Mock(),
    )

    mock_create_experiment = mock.Mock()
    mock_create_experiment.return_value = real_experiment

    mock_get_experiment_url_by_id = mock.Mock()
    mock_get_experiment_url_by_id.return_value = "any_url"

    def compute_accuracy_stats(test_results: List) -> List[score_result.ScoreResult]:
        """Compute max accuracy across all test results."""
        accuracy_scores = [
            score.value
            for test_result in test_results
            for score in test_result.score_results
            if score.name == "equals_metric"
        ]
        if not accuracy_scores:
            return []
        return [
            score_result.ScoreResult(
                name="equals_metric (max)",
                value=max(accuracy_scores),
            ),
        ]

    with mock.patch.object(
        opik_client.Opik, "create_experiment", mock_create_experiment
    ):
        with mock.patch.object(
            url_helpers, "get_experiment_url_by_id", mock_get_experiment_url_by_id
        ):
            result = evaluation.evaluate(
                dataset=mock_dataset,
                task=say_task,
                experiment_name="test-experiment",
                scoring_metrics=[metrics.Equals()],
                task_threads=1,
                experiment_scoring_functions=[compute_accuracy_stats],
            )

    # Verify experiment scores were computed and stored
    assert len(result.experiment_scores) == 1
    assert result.experiment_scores[0].name == "equals_metric (max)"
    assert result.experiment_scores[0].value == 1.0

    # Verify experiment scores were logged to backend
    mock_update_experiment.assert_called_once()
    call_args = mock_update_experiment.call_args
    assert call_args[1]["id"] == "experiment-id"
    assert len(call_args[1]["experiment_scores"]) == 1
    assert call_args[1]["experiment_scores"][0].name == "equals_metric (max)"
    assert call_args[1]["experiment_scores"][0].value == 1.0


def test_evaluate__with_experiment_scores_empty_results(fake_backend):
    """Test that experiment_scores handle empty test results gracefully."""
    mock_dataset = mock.MagicMock(
        spec=[
            "__internal_api__stream_items_as_dataclasses__",
            "id",
            "name",
            "dataset_items_count",
            "get_version_info",
            "get_execution_policy",
            "get_evaluators",
        ]
    )
    mock_dataset.name = "test-dataset"
    mock_dataset.get_version_info.return_value = None
    mock_dataset.get_execution_policy.return_value = {
        "runs_per_item": 1,
        "pass_threshold": 1,
    }
    mock_dataset.get_evaluators.return_value = []
    mock_dataset.dataset_items_count = None
    mock_dataset.id = "dataset-id"
    mock_dataset.__internal_api__stream_items_as_dataclasses__.return_value = iter([])

    def say_task(dataset_item: Dict[str, Any]):
        return {"output": "hello"}

    mock_experiment = mock.Mock()
    mock_experiment.id = "experiment-id"
    mock_experiment.name = "test-experiment"
    mock_create_experiment = mock.Mock()
    mock_create_experiment.return_value = mock_experiment

    mock_get_experiment_url_by_id = mock.Mock()
    mock_get_experiment_url_by_id.return_value = "any_url"

    def compute_accuracy_stats(test_results: List) -> List[score_result.ScoreResult]:
        """Compute max accuracy across all test results."""
        return [
            score_result.ScoreResult(
                name="equals_metric (max)",
                value=0.5,
            ),
        ]

    with mock.patch.object(
        opik_client.Opik, "create_experiment", mock_create_experiment
    ):
        with mock.patch.object(
            url_helpers, "get_experiment_url_by_id", mock_get_experiment_url_by_id
        ):
            result = evaluation.evaluate(
                dataset=mock_dataset,
                task=say_task,
                experiment_name="test-experiment",
                scoring_metrics=[metrics.Equals()],
                task_threads=1,
                experiment_scoring_functions=[compute_accuracy_stats],
            )

    # Verify experiment scores are empty when no test results
    assert len(result.experiment_scores) == 0


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


def test_evaluate__uses_streaming_by_default(fake_backend):
    """Test that evaluate uses streaming mode by default when no dataset_item_ids or dataset_sampler is provided."""
    mock_dataset = mock.MagicMock(
        spec=[
            "__internal_api__stream_items_as_dataclasses__",
            "id",
            "dataset_items_count",
            "get_version_info",
            "get_execution_policy",
            "get_evaluators",
        ]
    )
    mock_dataset.name = "the-dataset-name"
    mock_dataset.dataset_items_count = None
    mock_dataset.get_version_info.return_value = None
    mock_dataset.get_execution_policy.return_value = {
        "runs_per_item": 1,
        "pass_threshold": 1,
    }
    mock_dataset.get_evaluators.return_value = []

    # Mock the streaming method to return an iterator
    mock_dataset.__internal_api__stream_items_as_dataclasses__.return_value = iter(
        [
            dataset_item.DatasetItem(
                id="dataset-item-id-1",
                input={"message": "say hello"},
                reference="hello",
            ),
        ]
    )

    def say_task(dataset_item: Dict[str, Any]):
        return {"output": "hello"}

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

    # Verify streaming method was called and non-streaming was not
    mock_dataset.__internal_api__stream_items_as_dataclasses__.assert_called_once_with(
        nb_samples=None,
        dataset_item_ids=None,
        batch_size=evaluator_module.EVALUATION_STREAM_DATASET_BATCH_SIZE,
        filter_string=None,
    )


def test_evaluate__uses_streaming_with_dataset_item_ids(fake_backend):
    """Test that evaluate uses streaming mode even when dataset_item_ids is provided."""
    mock_dataset = mock.MagicMock(
        spec=[
            "__internal_api__stream_items_as_dataclasses__",
            "id",
            "dataset_items_count",
            "get_version_info",
            "get_execution_policy",
            "get_evaluators",
        ]
    )
    mock_dataset.name = "the-dataset-name"
    mock_dataset.dataset_items_count = None
    mock_dataset.get_version_info.return_value = None
    mock_dataset.get_execution_policy.return_value = {
        "runs_per_item": 1,
        "pass_threshold": 1,
    }
    mock_dataset.get_evaluators.return_value = []
    mock_dataset.__internal_api__stream_items_as_dataclasses__.return_value = iter(
        [
            dataset_item.DatasetItem(
                id="dataset-item-id-1",
                input={"message": "say hello"},
                reference="hello",
            ),
        ]
    )

    def say_task(dataset_item: Dict[str, Any]):
        return {"output": "hello"}

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
                dataset_item_ids=["dataset-item-id-1"],
            )

    # Verify streaming method was called with dataset_item_ids
    mock_dataset.__internal_api__stream_items_as_dataclasses__.assert_called_once_with(
        nb_samples=None,
        dataset_item_ids=["dataset-item-id-1"],
        batch_size=evaluator_module.EVALUATION_STREAM_DATASET_BATCH_SIZE,
        filter_string=None,
    )


def test_evaluate__falls_back_to_non_streaming_with_dataset_sampler(fake_backend):
    """Test that evaluate falls back to non-streaming mode when dataset_sampler is provided."""
    mock_dataset = mock.MagicMock(
        spec=[
            "__internal_api__stream_items_as_dataclasses__",
            "id",
            "dataset_items_count",
            "get_version_info",
            "get_execution_policy",
            "get_evaluators",
        ]
    )
    mock_dataset.name = "the-dataset-name"
    mock_dataset.dataset_items_count = None
    mock_dataset.get_version_info.return_value = None
    mock_dataset.get_execution_policy.return_value = {
        "runs_per_item": 1,
        "pass_threshold": 1,
    }
    mock_dataset.get_evaluators.return_value = []
    mock_dataset.__internal_api__stream_items_as_dataclasses__.return_value = iter(
        [
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
    )

    def say_task(dataset_item: Dict[str, Any]):
        return {"output": "hello"}

    mock_experiment = mock.Mock()
    mock_create_experiment = mock.Mock()
    mock_create_experiment.return_value = mock_experiment

    mock_get_experiment_url_by_id = mock.Mock()
    mock_get_experiment_url_by_id.return_value = "any_url"

    sampler = samplers.RandomDatasetSampler(max_samples=1)

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

    # Verify streaming method was called (but list() was used to exhaust it for sampling)
    mock_dataset.__internal_api__stream_items_as_dataclasses__.assert_called_once_with(
        nb_samples=None,
        dataset_item_ids=None,
        batch_size=evaluator_module.EVALUATION_STREAM_DATASET_BATCH_SIZE,
        filter_string=None,
    )


def test_evaluate__streaming_with_nb_samples(fake_backend):
    """Test that streaming mode correctly passes nb_samples parameter."""
    mock_dataset = mock.MagicMock(
        spec=[
            "__internal_api__get_items_as_dataclasses__",
            "__internal_api__stream_items_as_dataclasses__",
            "id",
            "name",
            "dataset_items_count",
            "get_version_info",
            "get_execution_policy",
            "get_evaluators",
        ]
    )
    mock_dataset.get_version_info.return_value = None
    mock_dataset.get_execution_policy.return_value = {
        "runs_per_item": 1,
        "pass_threshold": 1,
    }
    mock_dataset.get_evaluators.return_value = []
    mock_dataset.name = "the-dataset-name"
    mock_dataset.dataset_items_count = None

    # Mock the streaming method to return an iterator with limited items
    mock_dataset.__internal_api__stream_items_as_dataclasses__.return_value = iter(
        [
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
    )

    def say_task(dataset_item: Dict[str, Any]):
        return {"output": "hello"}

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
                nb_samples=2,
            )

    # Verify streaming method was called with nb_samples parameter and non-streaming was not
    mock_dataset.__internal_api__stream_items_as_dataclasses__.assert_called_once_with(
        nb_samples=2,
        dataset_item_ids=None,
        batch_size=evaluator_module.EVALUATION_STREAM_DATASET_BATCH_SIZE,
        filter_string=None,
    )


def test_evaluate_prompt__with_filter_string__passes_to_streaming(fake_backend):
    """Test that evaluate_prompt correctly passes filter_string to streaming method."""
    MODEL_NAME = "gpt-3.5-turbo"
    filter_string = 'tags contains "important"'

    mock_dataset = create_mock_dataset(
        items=[
            dataset_item.DatasetItem(
                id="dataset-item-id-1",
                question="Hello, world!",
                reference="Hello, world!",
            ),
        ]
    )

    mock_experiment, mock_create_experiment, mock_get_experiment_url_by_id = (
        create_mock_experiment()
    )

    mock_models_factory_get, mock_model = create_mock_model(model_name=MODEL_NAME)

    with patch_evaluation_dependencies(
        mock_create_experiment,
        mock_get_experiment_url_by_id,
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
            dataset_filter_string=filter_string,
        )

    mock_dataset.__internal_api__stream_items_as_dataclasses__.assert_called_once_with(
        nb_samples=None,
        dataset_item_ids=None,
        batch_size=evaluator_module.EVALUATION_STREAM_DATASET_BATCH_SIZE,
        filter_string=filter_string,
    )


def test_evaluate_prompt__with_filter_string_and_nb_samples__passes_both_parameters(
    fake_backend,
):
    """Test that evaluate_prompt correctly passes both filter_string and nb_samples to streaming method."""
    MODEL_NAME = "gpt-3.5-turbo"
    filter_string = 'data.category = "test"'

    mock_dataset = create_mock_dataset(
        items=[
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
    )

    mock_experiment, mock_create_experiment, mock_get_experiment_url_by_id = (
        create_mock_experiment()
    )

    mock_models_factory_get, mock_model = create_mock_model(model_name=MODEL_NAME)

    with patch_evaluation_dependencies(
        mock_create_experiment,
        mock_get_experiment_url_by_id,
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
            nb_samples=2,
            dataset_filter_string=filter_string,
        )

    mock_dataset.__internal_api__stream_items_as_dataclasses__.assert_called_once_with(
        nb_samples=2,
        dataset_item_ids=None,
        batch_size=evaluator_module.EVALUATION_STREAM_DATASET_BATCH_SIZE,
        filter_string=filter_string,
    )


def test_evaluate_prompt__with_filter_string_and_dataset_sampler__passes_filter_string(
    fake_backend,
):
    """Test that evaluate_prompt passes filter_string even when dataset_sampler is used."""
    MODEL_NAME = "gpt-3.5-turbo"
    sampler = samplers.RandomDatasetSampler(max_samples=1)
    filter_string = 'created_at >= "2024-01-01T00:00:00Z"'

    mock_dataset = create_mock_dataset(
        items=[
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
    )

    mock_experiment, mock_create_experiment, mock_get_experiment_url_by_id = (
        create_mock_experiment()
    )

    mock_models_factory_get, mock_model = create_mock_model(model_name=MODEL_NAME)

    with patch_evaluation_dependencies(
        mock_create_experiment,
        mock_get_experiment_url_by_id,
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
            dataset_filter_string=filter_string,
        )

    mock_dataset.__internal_api__stream_items_as_dataclasses__.assert_called_once_with(
        nb_samples=None,
        dataset_item_ids=None,
        batch_size=evaluator_module.EVALUATION_STREAM_DATASET_BATCH_SIZE,
        filter_string=filter_string,
    )


def test_evaluate__with_filter_string__passes_to_streaming(fake_backend):
    """Test that evaluate correctly passes filter_string to streaming method."""
    filter_string = 'tags contains "important"'

    mock_dataset = create_mock_dataset(
        items=[
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
    )

    def say_task(dataset_item: Dict[str, Any]):
        return {"output": "hello"}

    mock_experiment, mock_create_experiment, mock_get_experiment_url_by_id = (
        create_mock_experiment()
    )

    with patch_evaluation_dependencies(
        mock_create_experiment,
        mock_get_experiment_url_by_id,
    ):
        evaluation.evaluate(
            dataset=mock_dataset,
            task=say_task,
            experiment_name="the-experiment-name",
            scoring_metrics=[metrics.Equals()],
            task_threads=1,
            dataset_filter_string=filter_string,
        )

    mock_dataset.__internal_api__stream_items_as_dataclasses__.assert_called_once_with(
        nb_samples=None,
        dataset_item_ids=None,
        batch_size=evaluator_module.EVALUATION_STREAM_DATASET_BATCH_SIZE,
        filter_string=filter_string,
    )


def test_evaluate__with_filter_string_and_nb_samples__passes_both_parameters(
    fake_backend,
):
    """Test that evaluate correctly passes both filter_string and nb_samples to streaming method."""
    filter_string = 'data.category = "test"'

    mock_dataset = create_mock_dataset(
        items=[
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
    )

    def say_task(dataset_item: Dict[str, Any]):
        return {"output": "hello"}

    mock_experiment, mock_create_experiment, mock_get_experiment_url_by_id = (
        create_mock_experiment()
    )

    with patch_evaluation_dependencies(
        mock_create_experiment,
        mock_get_experiment_url_by_id,
    ):
        evaluation.evaluate(
            dataset=mock_dataset,
            task=say_task,
            experiment_name="the-experiment-name",
            scoring_metrics=[metrics.Equals()],
            task_threads=1,
            nb_samples=2,
            dataset_filter_string=filter_string,
        )

    mock_dataset.__internal_api__stream_items_as_dataclasses__.assert_called_once_with(
        nb_samples=2,
        dataset_item_ids=None,
        batch_size=evaluator_module.EVALUATION_STREAM_DATASET_BATCH_SIZE,
        filter_string=filter_string,
    )


def test_evaluate__with_filter_string_and_dataset_sampler__passes_filter_string(
    fake_backend,
):
    """Test that evaluate passes filter_string even when dataset_sampler is used."""
    sampler = samplers.RandomDatasetSampler(max_samples=1)
    filter_string = 'created_at >= "2024-01-01T00:00:00Z"'

    mock_dataset = create_mock_dataset(
        items=[
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
    )

    def say_task(dataset_item: Dict[str, Any]):
        return {"output": "hello"}

    mock_experiment, mock_create_experiment, mock_get_experiment_url_by_id = (
        create_mock_experiment()
    )

    with patch_evaluation_dependencies(
        mock_create_experiment,
        mock_get_experiment_url_by_id,
    ):
        evaluation.evaluate(
            dataset=mock_dataset,
            task=say_task,
            experiment_name="the-experiment-name",
            scoring_metrics=[metrics.Equals()],
            task_threads=1,
            dataset_sampler=sampler,
            dataset_filter_string=filter_string,
        )

    mock_dataset.__internal_api__stream_items_as_dataclasses__.assert_called_once_with(
        nb_samples=None,
        dataset_item_ids=None,
        batch_size=evaluator_module.EVALUATION_STREAM_DATASET_BATCH_SIZE,
        filter_string=filter_string,
    )


def test_evaluate_optimization_trial__with_filter_string__passes_to_streaming(
    fake_backend,
):
    """Test that evaluate_optimization_trial correctly passes filter_string to streaming method."""
    filter_string = 'tags contains "test"'

    mock_dataset = create_mock_dataset(
        items=[
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
    )

    def say_task(dataset_item: Dict[str, Any]):
        return {"output": "hello"}

    mock_experiment, mock_create_experiment, mock_get_experiment_url_by_id = (
        create_mock_experiment()
    )

    with patch_evaluation_dependencies(
        mock_create_experiment,
        mock_get_experiment_url_by_id,
    ):
        evaluator_module.evaluate_optimization_trial(
            optimization_id="opt-123",
            dataset=mock_dataset,
            task=say_task,
            experiment_name="the-experiment-name",
            scoring_metrics=[metrics.Equals()],
            task_threads=1,
            dataset_filter_string=filter_string,
        )

    mock_dataset.__internal_api__stream_items_as_dataclasses__.assert_called_once_with(
        nb_samples=None,
        dataset_item_ids=None,
        batch_size=evaluator_module.EVALUATION_STREAM_DATASET_BATCH_SIZE,
        filter_string=filter_string,
    )


def test_evaluate__verbose_zero__progress_bar_disabled(fake_backend):
    """Test that verbose=0 disables the progress bar."""
    mock_dataset = create_mock_dataset(
        items=[
            dataset_item.DatasetItem(
                id="item-1", input={"message": "hello"}, reference="hello"
            ),
        ]
    )

    def say_task(item: Dict[str, Any]):
        return {"output": "hello"}

    mock_experiment, mock_create_experiment, mock_get_experiment_url_by_id = (
        create_mock_experiment()
    )

    with mock.patch(
        "opik.environment.get_tqdm_for_current_environment"
    ) as mock_get_tqdm:
        mock_tqdm_factory = mock.Mock()
        mock_progress_bar = mock.Mock()
        mock_tqdm_factory.return_value = mock_progress_bar
        mock_get_tqdm.return_value = mock_tqdm_factory

        with patch_evaluation_dependencies(
            mock_create_experiment, mock_get_experiment_url_by_id
        ):
            evaluation.evaluate(
                dataset=mock_dataset,
                task=say_task,
                experiment_name="verbose-off-test",
                scoring_metrics=[metrics.Equals()],
                task_threads=1,
                verbose=0,
            )

    # tqdm should be created with disable=True when verbose=0
    mock_tqdm_factory.assert_called_once_with(
        disable=True,
        desc=mock.ANY,
        total=mock.ANY,
    )
