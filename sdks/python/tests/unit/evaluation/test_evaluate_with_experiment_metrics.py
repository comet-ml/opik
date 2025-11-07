from typing import Any, Dict, List
from unittest import mock

from opik import evaluation, url_helpers
from opik.api_objects import opik_client
from opik.api_objects.dataset import dataset_item
from opik.evaluation import metrics, test_result
from opik.evaluation.metrics import experiment_metric_result


def test_evaluate__with_experiment_metrics__happyflow(fake_backend):
    """Test basic happy flow with experiment metrics returning a single result."""
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

    # Create experiment metric function
    def compute_max_metric(test_results: List[test_result.TestResult]):
        # Find max value for equals_metric
        max_value = 0.0
        for test_result_item in test_results:
            for score_result in test_result_item.score_results:
                if score_result.name == "equals_metric":
                    max_value = max(max_value, score_result.value)
        return experiment_metric_result.ExperimentMetricResult(
            score_name="equals_metric",
            metric_name="max",
            value=max_value,
        )

    mock_experiment = mock.Mock()
    mock_experiment.id = "experiment-id-123"
    mock_create_experiment = mock.Mock()
    mock_create_experiment.return_value = mock_experiment

    mock_get_experiment_url_by_id = mock.Mock()
    mock_get_experiment_url_by_id.return_value = "any_url"

    mock_update_experiment = mock.Mock(return_value=None)
    mock_rest_client = mock.Mock()
    mock_rest_client.experiments = mock.Mock()
    mock_rest_client.experiments.update_experiment = mock_update_experiment

    with mock.patch.object(
        opik_client.Opik, "create_experiment", mock_create_experiment
    ):
        with mock.patch.object(
            url_helpers, "get_experiment_url_by_id", mock_get_experiment_url_by_id
        ):
            # Patch get_client_cached to inject the mocked _rest_client
            original_get_client_cached = opik_client.get_client_cached

            def get_client_with_mocked_rest():
                client = original_get_client_cached()
                client._rest_client = mock_rest_client
                return client

            with mock.patch(
                "opik.api_objects.opik_client.get_client_cached",
                side_effect=get_client_with_mocked_rest,
            ):
                evaluation.evaluate(
                    dataset=mock_dataset,
                    task=say_task,
                    experiment_name="the-experiment-name",
                    scoring_metrics=[metrics.Equals()],
                    task_threads=1,
                    experiment_metrics=[compute_max_metric],
                )

    # Verify update_experiment was called with correct format
    mock_update_experiment.assert_called_once()
    call_args = mock_update_experiment.call_args
    assert call_args.kwargs["id"] == mock_experiment.id
    assert "pre_computed_metric_aggregates" in call_args.kwargs
    aggregates = call_args.kwargs["pre_computed_metric_aggregates"]
    assert isinstance(aggregates, dict)
    assert "equals_metric" in aggregates
    assert "max" in aggregates["equals_metric"]
    assert aggregates["equals_metric"]["max"] == 1.0  # Max of [1.0, 0.0]


def test_evaluate__with_experiment_metrics__list_of_results__happyflow(fake_backend):
    """Test experiment metrics function returning a list of results."""
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

    # Create experiment metric function that returns a list
    def compute_stats(test_results: List[test_result.TestResult]):
        scores = []
        for test_result_item in test_results:
            for score_result in test_result_item.score_results:
                if score_result.name == "equals_metric":
                    scores.append(score_result.value)

        if not scores:
            return []

        return [
            experiment_metric_result.ExperimentMetricResult(
                score_name="equals_metric",
                metric_name="max",
                value=max(scores),
            ),
            experiment_metric_result.ExperimentMetricResult(
                score_name="equals_metric",
                metric_name="min",
                value=min(scores),
            ),
        ]

    mock_experiment = mock.Mock()
    mock_experiment.id = "experiment-id-123"
    mock_create_experiment = mock.Mock()
    mock_create_experiment.return_value = mock_experiment

    mock_get_experiment_url_by_id = mock.Mock()
    mock_get_experiment_url_by_id.return_value = "any_url"

    mock_update_experiment = mock.Mock(return_value=None)
    mock_rest_client = mock.Mock()
    mock_rest_client.experiments = mock.Mock()
    mock_rest_client.experiments.update_experiment = mock_update_experiment

    with mock.patch.object(
        opik_client.Opik, "create_experiment", mock_create_experiment
    ):
        with mock.patch.object(
            url_helpers, "get_experiment_url_by_id", mock_get_experiment_url_by_id
        ):
            # Patch get_client_cached to inject the mocked _rest_client
            original_get_client_cached = opik_client.get_client_cached

            def get_client_with_mocked_rest():
                client = original_get_client_cached()
                client._rest_client = mock_rest_client
                return client

            with mock.patch(
                "opik.api_objects.opik_client.get_client_cached",
                side_effect=get_client_with_mocked_rest,
            ):
                evaluation.evaluate(
                    dataset=mock_dataset,
                    task=say_task,
                    experiment_name="the-experiment-name",
                    scoring_metrics=[metrics.Equals()],
                    task_threads=1,
                    experiment_metrics=[compute_stats],
                )

    # Verify update_experiment was called with both max and min
    mock_update_experiment.assert_called_once()
    call_args = mock_update_experiment.call_args
    aggregates = call_args.kwargs["pre_computed_metric_aggregates"]
    assert "equals_metric" in aggregates
    assert aggregates["equals_metric"]["max"] == 1.0
    assert aggregates["equals_metric"]["min"] == 0.0


def test_evaluate__with_experiment_metrics_and_scoring_metrics__happyflow(
    fake_backend,
):
    """Test integration of experiment metrics with scoring metrics."""
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

    # Create experiment metric function
    def compute_avg_metric(test_results: List[test_result.TestResult]):
        scores = []
        for test_result_item in test_results:
            for score_result in test_result_item.score_results:
                if score_result.name == "equals_metric":
                    scores.append(score_result.value)

        if not scores:
            return experiment_metric_result.ExperimentMetricResult(
                score_name="equals_metric",
                metric_name="avg",
                value=0.0,
            )

        avg_value = sum(scores) / len(scores)
        return experiment_metric_result.ExperimentMetricResult(
            score_name="equals_metric",
            metric_name="avg",
            value=avg_value,
        )

    mock_experiment = mock.Mock()
    mock_experiment.id = "experiment-id-123"
    mock_create_experiment = mock.Mock()
    mock_create_experiment.return_value = mock_experiment

    mock_get_experiment_url_by_id = mock.Mock()
    mock_get_experiment_url_by_id.return_value = "any_url"

    mock_update_experiment = mock.Mock(return_value=None)
    mock_rest_client = mock.Mock()
    mock_rest_client.experiments = mock.Mock()
    mock_rest_client.experiments.update_experiment = mock_update_experiment

    with mock.patch.object(
        opik_client.Opik, "create_experiment", mock_create_experiment
    ):
        with mock.patch.object(
            url_helpers, "get_experiment_url_by_id", mock_get_experiment_url_by_id
        ):
            # Patch get_client_cached to inject the mocked _rest_client
            original_get_client_cached = opik_client.get_client_cached

            def get_client_with_mocked_rest():
                client = original_get_client_cached()
                client._rest_client = mock_rest_client
                return client

            with mock.patch(
                "opik.api_objects.opik_client.get_client_cached",
                side_effect=get_client_with_mocked_rest,
            ):
                evaluation.evaluate(
                    dataset=mock_dataset,
                    task=say_task,
                    experiment_name="the-experiment-name",
                    scoring_metrics=[metrics.Equals()],
                    task_threads=1,
                    experiment_metrics=[compute_avg_metric],
                )

    # Verify both scoring metrics and experiment metrics work together
    # First verify traces were created with feedback scores
    assert len(fake_backend.trace_trees) == 2
    for trace in fake_backend.trace_trees:
        assert len(trace.feedback_scores) == 1
        assert trace.feedback_scores[0].name == "equals_metric"

    # Verify experiment metrics were uploaded
    mock_update_experiment.assert_called_once()
    call_args = mock_update_experiment.call_args
    aggregates = call_args.kwargs["pre_computed_metric_aggregates"]
    assert "equals_metric" in aggregates
    assert aggregates["equals_metric"]["avg"] == 0.5  # Average of [1.0, 0.0]
