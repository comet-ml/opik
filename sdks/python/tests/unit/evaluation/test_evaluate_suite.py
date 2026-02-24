"""Unit tests for evaluate_suite function — specifically that it passes
evaluation_method='evaluation_suite' when creating the experiment."""

from unittest import mock

from opik.evaluation import evaluator as evaluator_module


def _create_mock_dataset(name="test-dataset"):
    mock_dataset = mock.MagicMock()
    mock_dataset.name = name
    mock_dataset.id = "dataset-id-123"
    mock_dataset.dataset_items_count = 0
    mock_dataset.get_evaluators.return_value = []
    mock_dataset.get_execution_policy.return_value = {
        "runs_per_item": 1,
        "pass_threshold": 1,
    }
    mock_dataset.__internal_api__stream_items_as_dataclasses__ = mock.MagicMock(
        return_value=iter([])
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
            experiment_name="test-experiment",
            verbose=0,
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
            experiment_name="test-experiment-2",
            verbose=0,
        )

    call_kwargs = mock_client.create_experiment.call_args[1]
    assert call_kwargs["evaluation_method"] != "dataset"
    assert call_kwargs["evaluation_method"] == "evaluation_suite"
