from typing import Final

import opik.config
import urllib.parse
import base64

URL_ACCOUNT_DETAILS_POSTFIX: Final[str] = "api/rest/v2/account-details"
URL_WORKSPACE_GET_LIST_POSTFIX: Final[str] = "api/rest/v2/workspaces"
HEALTH_CHECK_URL_POSTFIX: Final[str] = "/is-alive/ping"
ALLOWED_URL_CHARACTERS: Final[str] = ":/&?="


def get_ui_url() -> str:
    config = opik.config.OpikConfig()
    opik_url_override = config.url_override

    return opik_url_override.rstrip("/api") + "/"


def get_experiment_url(dataset_name: str, experiment_id: str) -> str:
    client = opik.api_objects.opik_client.get_client_cached()

    # Get dataset id from name
    dataset = client._rest_client.datasets.get_dataset_by_identifier(
        dataset_name=dataset_name
    )
    dataset_id = dataset.id

    config = opik.config.OpikConfig()
    ui_url = get_ui_url()

    experiment_path = urllib.parse.quote(
        f'{config.workspace}/experiments/{dataset_id}/compare?experiments=["{experiment_id}"]',
        safe=ALLOWED_URL_CHARACTERS,
    )

    return urllib.parse.urljoin(ui_url, experiment_path)


def get_project_url_by_workspace(workspace: str, project_name: str) -> str:
    ui_url = get_ui_url()

    project_path = urllib.parse.quote(
        f"{workspace}/redirect/projects?name={project_name}",
        safe=ALLOWED_URL_CHARACTERS,
    )
    return urllib.parse.urljoin(ui_url, project_path)


def get_project_url_by_trace_id(trace_id: str, url_override: str) -> str:
    encoded_opik_url = base64.b64encode(url_override.encode("utf-8")).decode("utf-8")
    project_path = urllib.parse.quote(
        f"v1/session/redirect/projects/?trace_id={trace_id}&path={encoded_opik_url}",
        safe=ALLOWED_URL_CHARACTERS,
    )
    return urllib.parse.urljoin(url_override, project_path)


def get_dataset_url(workspace: str, dataset_name: str) -> str:
    ui_url = get_ui_url()

    dataset_path = urllib.parse.quote(
        f"{workspace}/redirect/datasets?name={dataset_name}",
        safe=ALLOWED_URL_CHARACTERS,
    )

    return urllib.parse.urljoin(ui_url, dataset_path)


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
