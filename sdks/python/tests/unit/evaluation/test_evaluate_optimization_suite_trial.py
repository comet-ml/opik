"""Unit tests for evaluate_optimization_suite_trial."""

from typing import Any, Dict
from unittest import mock

from opik import url_helpers
from opik.api_objects import opik_client
from opik.api_objects.dataset import dataset_item
from opik.evaluation import evaluator as evaluator_module


def test_evaluate_optimization_suite_trial__creates_trial_experiment_and_forwards_filter_params(
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
    mock_dataset.name = "suite-dataset"
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
                id="item-1",
                input={"text": "hello"},
            ),
        ]
    )

    def task(item: Dict[str, Any]):
        return {"output": "result"}

    mock_experiment = mock.Mock()
    mock_create_experiment = mock.Mock(return_value=mock_experiment)
    mock_get_experiment_url = mock.Mock(return_value="any_url")

    dataset_item_ids = ["item-1", "item-2"]
    filter_string = 'input.text = "hello"'

    with mock.patch.object(
        opik_client.Opik, "create_experiment", mock_create_experiment
    ):
        with mock.patch.object(
            url_helpers, "get_experiment_url_by_id", mock_get_experiment_url
        ):
            evaluator_module.evaluate_optimization_suite_trial(
                optimization_id="opt-123",
                dataset=mock_dataset,
                task=task,
                experiment_name="trial-exp",
                dataset_item_ids=dataset_item_ids,
                dataset_filter_string=filter_string,
                task_threads=1,
                verbose=0,
            )

    mock_create_experiment.assert_called_once_with(
        name="trial-exp",
        dataset_name="suite-dataset",
        experiment_config=None,
        prompts=None,
        type="trial",
        optimization_id="opt-123",
        tags=None,
        dataset_version_id=None,
    )

    mock_dataset.__internal_api__stream_items_as_dataclasses__.assert_called_once_with(
        nb_samples=None,
        dataset_item_ids=dataset_item_ids,
        batch_size=mock.ANY,
        filter_string=filter_string,
    )
