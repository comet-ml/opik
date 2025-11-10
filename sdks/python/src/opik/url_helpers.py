import base64
import urllib.parse
from typing import Final

import opik.config

from typing import TYPE_CHECKING

if TYPE_CHECKING:
    import opik


URL_ACCOUNT_DETAILS_POSTFIX: Final[str] = "api/rest/v2/account-details"
URL_WORKSPACE_GET_LIST_POSTFIX: Final[str] = "api/rest/v2/workspaces"
HEALTH_CHECK_URL_POSTFIX: Final[str] = "/is-alive/ping"
ALLOWED_URL_CHARACTERS: Final[str] = ":/&?="


def ensure_ending_slash(url: str) -> str:
    return url.rstrip("/") + "/"


def get_ui_url() -> str:
    config = opik.config.OpikConfig()
    opik_url_override = config.url_override

    return opik_url_override.rstrip("/api") + "/"


def get_experiment_url_by_id(
    dataset_id: str, experiment_id: str, url_override: str
) -> str:
    encoded_opik_url = base64.b64encode(url_override.encode("utf-8")).decode("utf-8")

    project_path = urllib.parse.quote(
        f"v1/session/redirect/experiments/?experiment_id={experiment_id}&dataset_id={dataset_id}&path={encoded_opik_url}",
        safe=ALLOWED_URL_CHARACTERS,
    )
    return urllib.parse.urljoin(ensure_ending_slash(url_override), project_path)


def get_project_url_by_workspace(
    workspace: str, project_name: str
) -> str:  # don't use or update, will be removed soon
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
    return urllib.parse.urljoin(ensure_ending_slash(url_override), project_path)


def get_dataset_url_by_id(dataset_id: str, url_override: str) -> str:
    encoded_opik_url = base64.b64encode(url_override.encode("utf-8")).decode("utf-8")

    project_path = urllib.parse.quote(
        f"v1/session/redirect/datasets/?dataset_id={dataset_id}&path={encoded_opik_url}",
        safe=ALLOWED_URL_CHARACTERS,
    )
    return urllib.parse.urljoin(ensure_ending_slash(url_override), project_path)


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


def is_aws_presigned_url(url: str) -> bool:
    return "X-Amz-Signature" in url or (
        "Signature=" in url and "AWSAccessKeyId=" in url
    )
