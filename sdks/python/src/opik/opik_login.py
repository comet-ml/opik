"""

"""

import logging
import sys
from getpass import getpass
from typing import Final, Optional

import httpx

import opik.config
from opik import httpx_client
from opik.config import OPIK_BASE_URL_CLOUD, OPIK_WORKSPACE_DEFAULT_NAME
from opik.exceptions import ConfigurationError

LOGGER = logging.getLogger(__name__)

HEALTH_CHECK_URL_POSTFIX: Final[str] = '/is-alive/ping'
HEALTH_CHECK_TIMEOUT: Final[float] = 1.0


def is_interactive() -> bool:
    """
    Returns True if in interactive mode
    """
    return bool(getattr(sys, "ps1", sys.flags.interactive))


def is_instance_active(url: str) -> bool:
    """
    Returns True if given Opik URL responds to an HTTP GET request.
    """
    http_client = httpx_client.get(
        workspace=OPIK_WORKSPACE_DEFAULT_NAME,
        api_key=None,
    )

    http_client.timeout = HEALTH_CHECK_TIMEOUT
    response = http_client.get(url=url + HEALTH_CHECK_URL_POSTFIX)

    if response.status_code == 200:
        return True

    return False


def is_workspace_name_correct(api_key: str, workspace: str) -> bool:
    """
    Returns True if given cloud Opik workspace are correct.

    Raises:
        ConnectionError:

    """

    url = "https://www.comet.com/api/rest/v2/workspaces"

    client = httpx.Client()
    client.headers.update({
        "Authorization": f"{api_key}",
    })

    try:
        response = client.get(url=url)
    except Exception as e:
        raise ConnectionError(f"Error while checking workspace status: {str(e)}")

    if response.status_code != 200:
        raise ConnectionError(f"Error while checking workspace status: {response.text}")

    workspaces = response.json()["workspaceNames"]

    if workspace in workspaces:
        return True
    else:
        return False


def is_api_key_correct(api_key: str) -> bool:
    """
    Returns True if given cloud Opik API is correct.

    Raises:
        ConnectionError:
    """
    url = "https://www.comet.com/api/rest/v2/account-details"

    client = httpx.Client()
    client.headers.update({
        "Authorization": f"{api_key}",
    })

    try:
        response = client.get(url=url)

        if response.status_code == 200:
            return True
        elif response.status_code in [401, 403]:
            return False

        raise ConnectionError(f"Error while checking API key: {response.text}")

    except Exception as e:
        raise ConnectionError(f"Error while checking API key: {str(e)}")


def get_default_workspace(api_key: str) -> str:
    """
    Returns default Opik workspace name.

    Raises:
        ConnectionError:
    """
    url = "https://www.comet.com/api/rest/v2/account-details"

    client = httpx.Client()
    client.headers.update({
        "Authorization": f"{api_key}",
    })

    try:
        response = client.get(url=url)
    except Exception as e:
        raise ConnectionError(f"Error while getting default workspace name: {str(e)}")

    if response.status_code != 200:
        raise ConnectionError(f"Error while getting default workspace name: {response.text}")

    return response.json()["defaultWorkspaceName"]


def _update_config(
    api_key: Optional[str],
    url: str,
    workspace: str,
) -> None:
    """
    Save changes to config file and update current session config

    Args:
        api_key
        url
        workspace
    Raises:
        ConfigurationError
    """
    try:
        new_config = opik.config.OpikConfig(
            api_key=api_key,
            url_override=url,
            workspace=workspace,
        )
        new_config.save_to_file()

        # update session config
        opik.config.update_session_config("api_key", api_key)
        opik.config.update_session_config("url_override", url)
        opik.config.update_session_config("workspace", workspace)

        return

    except Exception as e:
        raise ConfigurationError(str(e))


def _ask_for_url() -> str:
    """
    Ask user for Opik instance URL and check if it is accessible.
    """
    retries = 2

    while retries > 0:
        user_input_opik_url = input("Please enter your Opik instance URL:")

        # Validate it is accessible using health
        if is_instance_active(user_input_opik_url):
            # If yes → Save
            return user_input_opik_url
        else:
            # If no → Retry up to 2 times - ? Add message to docs ?
            LOGGER.error("Wrong URL. Please try again.")
            retries -= 1

    raise ConfigurationError("Can't use URL provided by user. Opik instance is not active or not found.")


def _ask_for_api_key() -> str:
    """
    Ask user for cloud Opik instance API key and check if is it correct.
    """
    retries = 3

    while retries > 0:
        user_input_api_key = getpass("Please enter your cloud Opik instance API key:")

        if is_api_key_correct(user_input_api_key):
            return user_input_api_key
        else:
            LOGGER.error("Wrong API key. Please try again.")
            retries -= 1

    raise ConfigurationError("API key is incorrect.")


def _ask_for_workspace(api_key: str) -> str:
    """
    Ask user for cloud Opik instance workspace name.
    """
    retries = 3

    while retries > 0:
        user_input_workspace = input("Please enter your cloud Opik instance workspace name:")

        if is_workspace_name_correct(api_key, user_input_workspace):
            return user_input_workspace
        else:
            LOGGER.error("Wrong workspace name. Please try again.")
            retries -= 1

    raise ConfigurationError("Workspace name is incorrect.")


def ask_user_for_approval(message: str) -> bool:
    while True:
        users_choice = input(message)
        users_choice = users_choice.upper()

        if users_choice in ("Y", "YES", ""):
            return True

        if users_choice in ("N", "NO"):
            return False

        LOGGER.error("Wrong choice. Please try again.")


def login(
    api_key: Optional[str] = None,
    url: Optional[str] = None,
    workspace: Optional[str] = None,
    force: bool = False,
    use_local: bool = False
) -> None:
    """
    Args:
        api_key
        url
        workspace
        force: force to save passed settings only (no other config or env variables will be handled)
        use_local

    Raises:
        ConfigurationError
    """

    # OPIK CLOUD
    if use_local is False:
        _login_cloud(
            api_key=api_key,
            workspace=workspace,
            force=force
        )
        return

    # LOCAL OPIK DEPLOYMENT
    _login_local(
        api_key=api_key,
        url=url,
        workspace=workspace,
        force=force
    )
    return


def _login_cloud(
    api_key: Optional[str],
    workspace: Optional[str],
    force: bool
) -> None:
    """
    Login to cloud Opik instance
    """
    current_config = opik.config.OpikConfig()

    # first check parameters
    if (force is True and api_key is None) or (
            is_interactive() is False and api_key is None and current_config.api_key is None):
        raise ConfigurationError("No API key provided for cloud Opik instance.")

    if (force is True and workspace is None) or (
            is_interactive() is False and workspace is None and current_config.workspace is None):
        raise ConfigurationError("No workspace name provided for cloud Opik instance.")

    # Ask for API key
    if api_key is None and current_config.api_key is None:
        LOGGER.info("You can find your API key here: https://www.comet.com/api/my/settings/")
        api_key = _ask_for_api_key()

    # Check what their default workspace is, and we ask them if they want to use the default workspace
    if workspace is None:
        use_current_workspace = ask_user_for_approval(f"Do you want to use \"{current_config.workspace}\" workspace? Y/n")

        if use_current_workspace:
            workspace = current_config.workspace

            if not is_workspace_name_correct(api_key, workspace):
                LOGGER.warning("Workspace name is incorrect.")
                workspace = _ask_for_workspace(api_key=api_key)
        else:
            workspace = _ask_for_workspace(api_key=api_key)

    _update_config(
        api_key=api_key,
        url=OPIK_BASE_URL_CLOUD,
        workspace=workspace,
    )
    return


def _login_local(
    api_key: Optional[str],
    url: Optional[str],
    workspace: Optional[str],
    force: bool
) -> None:
    """
    Login to local Opik deployment

    Args:
        api_key
        url
        workspace
        force: force to save passed settings only (no other config or env variables will be handled)

    Raises:
        ConfigurationError
    """

    current_config = opik.config.OpikConfig()

    if (force is True and workspace is None) or (
            is_interactive() is False and workspace is None and current_config.workspace is None):
        raise ConfigurationError("No workspace name provided for cloud Opik instance.")

    if url is not None and is_instance_active(url):
        _update_config(
            api_key=api_key,
            url=url,
            workspace=workspace,
        )
        return
    else:
        LOGGER.warning("Opik URL is incorrect.")

    if is_instance_active(OPIK_BASE_URL_CLOUD):
        use_url = ask_user_for_approval(f"Found local Opik instance on: {OPIK_BASE_URL_CLOUD}\nDo you want to use it? Y/n")

        if use_url:
            _update_config(
                api_key=api_key,
                url=OPIK_BASE_URL_CLOUD,
                workspace=workspace,
            )
            return

    user_input_url = _ask_for_url()
    _update_config(
        api_key=api_key,
        url=user_input_url,
        workspace=workspace,
    )
    return
