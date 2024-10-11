import opik.config


def get_ui_url() -> str:
    config = opik.config.OpikConfig()
    opik_url_override = config.url_override

    return opik_url_override.rstrip("/api")


def get_experiment_url(dataset_name: str, experiment_id: str) -> str:
    client = opik.api_objects.opik_client.get_client_cached()

    # Get dataset id from name
    dataset = client._rest_client.datasets.get_dataset_by_identifier(
        dataset_name=dataset_name
    )
    dataset_id = dataset.id

    config = opik.config.OpikConfig()
    ui_url = get_ui_url()

    return f"{ui_url}/{config.workspace}/experiments/{dataset_id}/compare?experiments=%5B%22{experiment_id}%22%5D"


def get_projects_url(workspace: str) -> str:
    ui_url = get_ui_url()
    return f"{ui_url}/{workspace}/projects"
