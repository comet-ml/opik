from unittest.mock import Mock

from opik.api_objects.experiment import experiment as experiment_module


def test_dataset_id__looks_up_the_dataset_in_the_experiment_project():
    rest_client = Mock()
    rest_client.datasets.get_dataset_by_identifier.return_value = Mock(id="dataset-id")
    experiment = experiment_module.Experiment(
        id="experiment-id",
        name="experiment-name",
        dataset_name="dataset-name",
        rest_client=rest_client,
        streamer=Mock(),
        experiments_client=Mock(),
        project_name="project-name",
    )

    assert experiment.dataset_id == "dataset-id"
    rest_client.datasets.get_dataset_by_identifier.assert_called_once_with(
        dataset_name="dataset-name", project_name="project-name"
    )
