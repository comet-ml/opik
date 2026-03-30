"""Unit tests for evaluate_suite function — specifically that it passes
evaluation_method='evaluation_suite' when creating the experiment."""

import unittest.mock as mock

from opik import url_helpers
from opik.api_objects import opik_client
from opik.api_objects.dataset import dataset_item
from opik.evaluation import evaluator as evaluator_module

from ...testlib import ANY_BUT_NONE, SpanModel, assert_equal
from ...testlib.models import TraceModel


def _create_mock_dataset(name="test-dataset", items=None):
    mock_dataset = mock.MagicMock()
    mock_dataset.name = name
    mock_dataset.id = "dataset-id-123"
    mock_dataset.dataset_items_count = len(items) if items else 0
    mock_dataset.get_evaluators.return_value = []
    mock_dataset.get_execution_policy.return_value = {
        "runs_per_item": 1,
        "pass_threshold": 1,
    }
    mock_dataset.__internal_api__stream_items_as_dataclasses__ = mock.MagicMock(
        return_value=iter(items if items else [])
    )
    return mock_dataset


def test_evaluate_suite__creates_experiment_with_evaluation_method_evaluation_suite():
    mock_dataset = _create_mock_dataset()
    mock_experiment = mock.MagicMock()
    mock_experiment.id = "exp-123"
    mock_experiment.name = "test-experiment"

    mock_client = mock.MagicMock()
    mock_client.create_experiment.return_value = mock_experiment

    with (
        mock.patch.object(
            evaluator_module.opik_client,
            "get_client_cached",
            return_value=mock_client,
        ),
        mock.patch.object(
            evaluator_module.url_helpers,
            "get_experiment_url_by_id",
            return_value="http://example.com/exp",
        ),
    ):
        evaluator_module.evaluate_suite(
            dataset=mock_dataset,
            task=lambda item: {"input": item, "output": "response"},
            client=None,
            dataset_item_ids=None,
            dataset_filter_string=None,
            experiment_name_prefix=None,
            experiment_name="test-experiment",
            project_name=None,
            experiment_config=None,
            prompts=None,
            experiment_tags=None,
            verbose=0,
            task_threads=1,
            evaluator_model=None,
            optimization_id=None,
            experiment_type=None,
        )

    mock_client.create_experiment.assert_called_once()
    call_kwargs = mock_client.create_experiment.call_args[1]
    assert call_kwargs["evaluation_method"] == "evaluation_suite"


def test_evaluate_suite__passes_evaluation_method_not_dataset():
    """Verify it's specifically 'evaluation_suite', not 'dataset'."""
    mock_dataset = _create_mock_dataset()
    mock_experiment = mock.MagicMock()
    mock_experiment.id = "exp-456"
    mock_experiment.name = "test-experiment-2"

    mock_client = mock.MagicMock()
    mock_client.create_experiment.return_value = mock_experiment

    with (
        mock.patch.object(
            evaluator_module.opik_client,
            "get_client_cached",
            return_value=mock_client,
        ),
        mock.patch.object(
            evaluator_module.url_helpers,
            "get_experiment_url_by_id",
            return_value="http://example.com/exp",
        ),
    ):
        evaluator_module.evaluate_suite(
            dataset=mock_dataset,
            task=lambda item: {"input": item, "output": "response"},
            client=None,
            dataset_item_ids=None,
            dataset_filter_string=None,
            experiment_name_prefix=None,
            experiment_name="test-experiment-2",
            project_name=None,
            experiment_config=None,
            prompts=None,
            experiment_tags=None,
            verbose=0,
            task_threads=1,
            evaluator_model=None,
            optimization_id=None,
            experiment_type=None,
        )

    call_kwargs = mock_client.create_experiment.call_args[1]
    assert call_kwargs["evaluation_method"] != "dataset"
    assert call_kwargs["evaluation_method"] == "evaluation_suite"


def _call_evaluate_suite(optimization_id, items):
    """Helper that runs evaluate_suite through the real streamer pipeline."""
    mock_dataset = _create_mock_dataset(items=items)

    mock_experiment = mock.Mock()
    mock_experiment.id = "exp-789"
    mock_experiment.name = "source-test-experiment"

    mock_create_experiment = mock.Mock(return_value=mock_experiment)
    mock_get_url = mock.Mock(return_value="any_url")

    def simple_task(item):
        return {"output": "response"}

    with mock.patch.object(
        opik_client.Opik, "create_experiment", mock_create_experiment
    ):
        with mock.patch.object(url_helpers, "get_experiment_url_by_id", mock_get_url):
            evaluator_module.evaluate_suite(
                dataset=mock_dataset,
                task=simple_task,
                client=None,
                dataset_item_ids=None,
                dataset_filter_string=None,
                experiment_name_prefix=None,
                experiment_name="source-test-experiment",
                project_name=None,
                experiment_config=None,
                prompts=None,
                experiment_tags=None,
                verbose=0,
                task_threads=1,
                evaluator_model=None,
                optimization_id=optimization_id,
                experiment_type=None,
            )


def test_evaluate_suite__without_optimization_id__trace_tree_source_experiment_and_spans_source_experiment(
    fake_backend,
):
    """When optimization_id is not set → trace and task span both carry source='experiment' (not 'optimization')."""
    items = [
        dataset_item.DatasetItem(
            id="item-1", input={"message": "hello"}, reference="hello"
        ),
    ]
    _call_evaluate_suite(optimization_id=None, items=items)

    expected = TraceModel(
        id=ANY_BUT_NONE,
        name="evaluation_task",
        input=ANY_BUT_NONE,
        output=ANY_BUT_NONE,
        start_time=ANY_BUT_NONE,
        end_time=ANY_BUT_NONE,
        last_updated_at=ANY_BUT_NONE,
        source="experiment",
        spans=[
            SpanModel(
                id=ANY_BUT_NONE,
                name="simple_task",
                type="general",
                input=ANY_BUT_NONE,
                output=ANY_BUT_NONE,
                start_time=ANY_BUT_NONE,
                end_time=ANY_BUT_NONE,
                spans=[],
                source="experiment",
            ),
            SpanModel(
                id=ANY_BUT_NONE,
                name="metrics_calculation",
                type="general",
                input=ANY_BUT_NONE,
                output=ANY_BUT_NONE,
                start_time=ANY_BUT_NONE,
                end_time=ANY_BUT_NONE,
                spans=[],
                source="experiment",
            ),
        ],
    )

    assert len(fake_backend.trace_trees) == 1
    assert_equal(expected, fake_backend.trace_trees[0])


def test_evaluate_suite__with_optimization_id__trace_tree_source_optimization_and_spans_source_optimization(
    fake_backend,
):
    """When optimization_id is set → trace and task span both carry source='optimization'."""
    items = [
        dataset_item.DatasetItem(
            id="item-1", input={"message": "hello"}, reference="hello"
        ),
    ]
    _call_evaluate_suite(optimization_id="opt-789", items=items)

    expected = TraceModel(
        id=ANY_BUT_NONE,
        name="evaluation_task",
        input=ANY_BUT_NONE,
        output=ANY_BUT_NONE,
        start_time=ANY_BUT_NONE,
        end_time=ANY_BUT_NONE,
        last_updated_at=ANY_BUT_NONE,
        source="optimization",
        spans=[
            SpanModel(
                id=ANY_BUT_NONE,
                name="simple_task",
                type="general",
                input=ANY_BUT_NONE,
                output=ANY_BUT_NONE,
                start_time=ANY_BUT_NONE,
                end_time=ANY_BUT_NONE,
                spans=[],
                source="optimization",
            ),
            SpanModel(
                id=ANY_BUT_NONE,
                name="metrics_calculation",
                type="general",
                input=ANY_BUT_NONE,
                output=ANY_BUT_NONE,
                start_time=ANY_BUT_NONE,
                end_time=ANY_BUT_NONE,
                spans=[],
                source="optimization",
            ),
        ],
    )

    assert len(fake_backend.trace_trees) == 1
    assert_equal(expected, fake_backend.trace_trees[0])
