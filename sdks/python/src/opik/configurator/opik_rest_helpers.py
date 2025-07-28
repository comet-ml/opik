import logging
from typing import Final, List, Optional

import httpx

from opik.exceptions import ConfigurationError
import opik.url_helpers as url_helpers
import opik.config as config
import opik.httpx_client as httpx_client

LOGGER = logging.getLogger(__name__)

HEALTH_CHECK_TIMEOUT: Final[float] = 1.0


def _get_httpx_client(api_key: Optional[str] = None) -> httpx.Client:
    config_ = config.OpikConfig()
    client = httpx_client.get(
        workspace=None,
        api_key=api_key,
        check_tls_certificate=config_.check_tls_certificate,
        compress_json_requests=config_.enable_json_request_compression,
    )

    return client


def is_instance_active(url: str) -> bool:
    """
    Returns True if the given Opik URL responds to an HTTP GET request.

    Args:
        url (str): The base URL of the instance to check.

    Returns:
        bool: True if the instance responds with HTTP status 200, otherwise False.
    """
    try:
        with _get_httpx_client() as http_client:
            response = http_client.get(
                url=url_helpers.get_is_alive_ping_url(url), timeout=HEALTH_CHECK_TIMEOUT
            )
        return response.status_code == 200
    except httpx.ConnectTimeout:
        return False
    except Exception:
        return False


def is_api_key_correct(api_key: str, url: str) -> bool:
    """
    Validates if the provided Opik API key is correct by sending a request to the cloud API.

    Returns:
        bool: True if the API key is valid (status 200), False if the key is invalid (status 401 or 403).

    Raises:
        ConnectionError: If a network-related error occurs or the response status is neither 200, 401, nor 403.
    """

    try:
        with _get_httpx_client(api_key) as client:
            response = client.get(url=url_helpers.get_account_details_url(url))
        if response.status_code == 200:
            return True
        elif response.status_code in [401, 403]:
            return False
        else:
            raise ConnectionError(f"Error while checking API key: {response.text}")
    except httpx.RequestError as e:
        raise ConnectionError(f"Network error occurred: {str(e)}")
    except Exception as e:
        raise ConnectionError(f"Unexpected error occurred: {str(e)}")


def is_workspace_name_correct(api_key: Optional[str], workspace: str, url: str) -> bool:
    """
    Verifies whether the provided workspace name exists in the user's cloud Opik account.

    Args:
        workspace (str): The name of the workspace to check.

    Returns:
        bool: True if the workspace is found, False otherwise.

    Raises:
        ConnectionError: Raised if there's an issue with connecting to the Opik service, or the response is not successful.
    """
    if not api_key:
        raise ConfigurationError("API key must be set to check workspace name.")

    try:
        with _get_httpx_client(api_key) as client:
            response = client.get(url=url_helpers.get_workspace_list_url(url))
    except httpx.RequestError as e:
        # Raised for network-related errors such as timeouts
        raise ConnectionError(f"Network error: {str(e)}")
    except Exception as e:
        raise ConnectionError(f"Unexpected error occurred: {str(e)}")

    if response.status_code != 200:
        raise ConnectionError(f"HTTP error: {response.status_code} - {response.text}")

    workspaces: List[str] = response.json().get("workspaceNames", [])
    return workspace in workspaces
