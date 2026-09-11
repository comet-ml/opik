from typing import Optional, Any, Dict

from rich.console import Console

from opik import config
from opik.cli.healthcheck import opik_rest_helpers, rich_representation

console = Console()


def run(api_key: Optional[str], workspace: Optional[str]) -> None:
    rich_representation.print_header("user permissions")

    settings = config.OpikConfig()
    api_key = api_key or settings.api_key
    workspace = workspace or settings.workspace

    if api_key is None:
        raise ValueError("API key is required")
    if workspace is None:
        raise ValueError("Workspace is required")

    try:
        permissions = get_user_permissions(
            api_key, workspace=workspace, url=settings.url_override
        )
        rich_representation.print_user_permissions(permissions)
    except ConnectionError as e:
        err_msg = f"Failed to fetch user permissions:\n{e}"
        rich_representation.print_error_message(err_msg)


def get_user_permissions(api_key: str, workspace: str, url: str) -> Dict[str, Any]:
    rich_representation.print_opik_permissions_url(opik_url=url)
    return opik_rest_helpers.list_user_permissions(
        api_key, workspace=workspace, url=url
    )
