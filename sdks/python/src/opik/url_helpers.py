from typing import Final

import opik.config
import urllib.parse

URL_ACCOUNT_DETAILS_POSTFIX: Final[str] = "api/rest/v2/account-details"
URL_WORKSPACE_GET_LIST_POSTFIX: Final[str] = "api/rest/v2/workspaces"
HEALTH_CHECK_URL_POSTFIX: Final[str] = "/is-alive/ping"


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
    return f"{ui_url}/{workspace}/projects/"


def get_base_url(url: str) -> str:
    parsed = urllib.parse.urlparse(url)
    base_url = f"{parsed.scheme}://{parsed.netloc}/"

    return base_url


def get_account_details_url(base_url: str) -> str:
    return urllib.parse.urljoin(base_url, URL_ACCOUNT_DETAILS_POSTFIX)


def get_workspace_list_url(base_url: str) -> str:
    return urllib.parse.urljoin(base_url, URL_WORKSPACE_GET_LIST_POSTFIX)


def get_is_alive_ping_url(base_url: str) -> str:
    return urllib.parse.urljoin(base_url, HEALTH_CHECK_URL_POSTFIX)
