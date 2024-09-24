import getpass
import logging
from typing import Final, List, Optional, Tuple, cast

import httpx

import opik.config
from opik.config import (
    OPIK_BASE_URL_CLOUD,
    OPIK_BASE_URL_LOCAL,
    OPIK_WORKSPACE_DEFAULT_NAME,
)
from opik.exceptions import ConfigurationError

LOGGER = logging.getLogger(__name__)

HEALTH_CHECK_URL_POSTFIX: Final[str] = "/is-alive/ping"
HEALTH_CHECK_TIMEOUT: Final[float] = 1.0

URL_ACCOUNT_DETAILS: Final[str] = "https://www.comet.com/api/rest/v2/account-details"
URL_WORKSPACE_GET_LIST: Final[str] = "https://www.comet.com/api/rest/v2/workspaces"


def is_interactive() -> bool:
    """
    Returns True if in interactive mode
    """
    # return bool(getattr(sys, "ps1", sys.flags.interactive))
    return True


def is_instance_active(url: str) -> bool:
    """
    Returns True if the given Opik URL responds to an HTTP GET request.

    Args:
        url (str): The base URL of the instance to check.

    Returns:
        bool: True if the instance responds with HTTP status 200, otherwise False.
    """
    try:
        with httpx.Client(timeout=HEALTH_CHECK_TIMEOUT) as http_client:
            response = http_client.get(url=url + HEALTH_CHECK_URL_POSTFIX)
            return response.status_code == 200
    except httpx.ConnectTimeout:
        return False
    except Exception:
        return False


def is_workspace_name_correct(api_key: str, workspace: str) -> bool:
    """
    Verifies whether the provided workspace name exists in the user's cloud Opik account.

    Args:
        api_key (str): The API key used for authentication with the Opik service.
        workspace (str): The name of the workspace to check.

    Returns:
        bool: True if the workspace is found, False otherwise.

    Raises:
        ConnectionError: Raised if there's an issue with connecting to the Opik service, or the response is not successful.
    """

    try:
        with httpx.Client() as client:
            client.headers.update({"Authorization": f"{api_key}"})
            response = client.get(url=URL_WORKSPACE_GET_LIST)
    except httpx.RequestError as e:
        # Raised for network-related errors such as timeouts
        raise ConnectionError(f"Network error: {str(e)}")
    except Exception as e:
        raise ConnectionError(f"Unexpected error occurred: {str(e)}")

    if response.status_code != 200:
        raise ConnectionError(f"HTTP error: {response.status_code} - {response.text}")

    workspaces: List[str] = response.json().get("workspaceNames", [])

    return workspace in workspaces


def is_api_key_correct(api_key: str) -> bool:
    """
    Validates if the provided Opik API key is correct by sending a request to the cloud API.

    Args:
        api_key (str): The API key used for authentication.

    Returns:
        bool: True if the API key is valid (status 200), False if the key is invalid (status 401 or 403).

    Raises:
        ConnectionError: If a network-related error occurs or the response status is neither 200, 401, nor 403.
    """
    try:
        with httpx.Client() as client:
            client.headers.update({"Authorization": f"{api_key}"})
            response = client.get(url=URL_ACCOUNT_DETAILS)

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


def get_default_workspace(api_key: str) -> str:
    """
    Retrieves the default Opik workspace name associated with the given API key.

    Args:
        api_key (str): The API key used for authentication.

    Returns:
        str: The default workspace name.

    Raises:
        ConnectionError: If there's an error while fetching the default workspace.
    """
    try:
        with httpx.Client() as client:
            client.headers.update({"Authorization": f"{api_key}"})
            response = client.get(url=URL_ACCOUNT_DETAILS)

        if response.status_code != 200:
            raise ConnectionError(
                f"Error while getting default workspace name: {response.text}"
            )

        default_workspace_name = response.json().get("defaultWorkspaceName")
        if not default_workspace_name:
            raise ConnectionError("defaultWorkspaceName not found in the response.")

        return default_workspace_name

    except httpx.RequestError as e:
        raise ConnectionError(f"Network error occurred: {str(e)}")
    except Exception as e:
        raise ConnectionError(f"Unexpected error occurred: {str(e)}")


def _update_config(
    api_key: Optional[str],
    url: str,
    workspace: str,
) -> None:
    """
    Save changes to the config file and update the current session configuration.

    Args:
        api_key (Optional[str]): The API key for the Opik Cloud service. Can be None if not using Opik Cloud.
        url (str): The base URL of the Opik instance (local or cloud).
        workspace (str): The name of the workspace to be saved.

    Raises:
        ConfigurationError: Raised if there is an issue saving the configuration or updating the session.
    """
    try:
        new_config = opik.config.OpikConfig(
            api_key=api_key,
            url_override=url,
            workspace=workspace,
        )
        new_config.save_to_file()

        # Update current session configuration
        opik.config.update_session_config("api_key", api_key)
        opik.config.update_session_config("url_override", url)
        opik.config.update_session_config("workspace", workspace)

    except Exception as e:
        LOGGER.error(f"Failed to update config: {str(e)}")
        raise ConfigurationError("Failed to update configuration.")


def _ask_for_url() -> str:
    """
    Prompt the user for an Opik instance URL and check if it is accessible.
    The function retries up to 3 times if the URL is not accessible.

    Returns:
        str: A valid Opik instance URL.

    Raises:
        ConfigurationError: Raised if the URL provided by the user is not accessible after 3 attempts.
    """
    retries = 3

    while retries > 0:
        user_input_opik_url = input("Please enter your Opik instance URL:")

        if is_instance_active(user_input_opik_url):
            return user_input_opik_url
        else:
            LOGGER.error(
                f"Opik is not accessible at {user_input_opik_url}. Please try again, the URL should follow a format similar to {OPIK_BASE_URL_LOCAL}"
            )
            retries -= 1

    raise ConfigurationError(
        "Cannot use the URL provided by the user. Opik instance is not active or not found."
    )


def _ask_for_api_key() -> str:
    """
    Prompt the user for an Opik cloud API key and verify its validity.
    The function retries up to 3 times if the API key is invalid.

    Returns:
        str: A valid Opik API key.

    Raises:
        ConfigurationError: Raised if the API key provided by the user is invalid after 3 attempts.
    """
    retries = 3
    LOGGER.info(
        "Your Opik cloud API key is available at https://www.comet.com/api/my/settings/."
    )

    while retries > 0:
        user_input_api_key = getpass.getpass("Please enter your Opik Cloud API key:")

        if is_api_key_correct(user_input_api_key):
            return user_input_api_key
        else:
            LOGGER.error(
                f"The API key provided is not valid on {OPIK_BASE_URL_CLOUD}. Please try again."
            )
            retries -= 1

    raise ConfigurationError("API key is incorrect.")


def _ask_for_workspace(api_key: str) -> str:
    """
    Prompt the user for an Opik instance workspace name and verify its validity.

    The function retries up to 3 times if the workspace name is invalid.

    Args:
        api_key (str): The API key used to verify the workspace name.

    Returns:
        str: A valid workspace name.

    Raises:
        ConfigurationError: Raised if the workspace name is invalid after 3 attempts.
    """
    retries = 3

    while retries > 0:
        user_input_workspace = input(
            "Please enter your cloud Opik instance workspace name: "
        )

        if is_workspace_name_correct(api_key, user_input_workspace):
            return user_input_workspace
        else:
            LOGGER.error(
                "This workspace does not exist, please enter a workspace that you have access to."
            )
            retries -= 1

    raise ConfigurationError("User does not have access to the workspaces provided.")


def ask_user_for_approval(message: str) -> bool:
    """
    Prompt the user with a message for approval (Y/Yes/N/No).

    Args:
        message (str): The message to display to the user.

    Returns:
        bool: True if the user approves (Y/Yes/empty input), False if the user disapproves (N/No).

    Logs:
        Error when the user input is not recognized.
    """
    while True:
        users_choice = input(message).strip().upper()

        if users_choice in ("Y", "YES", ""):
            return True

        if users_choice in ("N", "NO"):
            return False

        LOGGER.error("Wrong choice. Please try again.")


def _get_api_key(
    api_key: Optional[str],
    current_config: opik.config.OpikConfig,
    force: bool,
) -> Tuple[str, bool]:
    """
    Determines the correct API key based on the current configuration, force flag, and user input.

    Args:
        api_key (Optional[str]): The user-provided API key.
        current_config (OpikConfig): The current configuration object.
        force (bool): Whether to force reconfiguration.

    Returns:
        Tuple[str, bool]: A tuple containing the validated API key and a boolean indicating
        if the configuration file needs updating.
    """
    config_file_needs_updating = False

    if force and api_key is None:
        api_key = _ask_for_api_key()
        config_file_needs_updating = True
    elif api_key is None and current_config.api_key is None:
        api_key = _ask_for_api_key()
        config_file_needs_updating = True
    elif api_key is None and current_config.api_key is not None:
        api_key = current_config.api_key
        # fixme if force is True -> need to save anyway?

    # todo add force and api_key is NOT None -> need to save?
    # todo force is False, api_key is not None -> need to save?

    # fixme is this check for mypy?
    # Ensure the API key is not None
    api_key = cast(str, api_key)

    return api_key, config_file_needs_updating


def _get_workspace(
    workspace: Optional[str],
    api_key: str,
    current_config: opik.config.OpikConfig,
    force: bool,
) -> Tuple[str, bool]:
    """
    Determines the correct workspace based on current configuration, force flag, and user input.

    Args:
        workspace (Optional[str]): The user-provided workspace name.
        api_key (str): The validated API key.
        current_config (OpikConfig): The current configuration object.
        force (bool): Whether to force reconfiguration.

    Returns:
        Tuple[str, bool]: The validated or selected workspace name and a boolean
        indicating whether the configuration file needs updating.

    Raises:
        ConfigurationError: If the provided workspace is invalid.
    """

    # Case 1: Workspace was provided by the user and is valid
    if workspace is not None:
        if not is_workspace_name_correct(api_key, workspace):
            raise ConfigurationError(
                "Workspace `%s` is incorrect for the given API key.", workspace
            )
        return workspace, True

    # Case 2: Use workspace from current configuration if not forced to change
    if (
        "workspace" in current_config.model_fields_set
        and current_config.workspace != OPIK_WORKSPACE_DEFAULT_NAME
        and not force
    ):
        return current_config.workspace, False

    # Case 3: No workspace provided, prompt the user
    default_workspace = get_default_workspace(api_key)
    use_default_workspace = ask_user_for_approval(
        f'Do you want to use "{default_workspace}" workspace? (Y/n)'
    )

    if use_default_workspace:
        workspace = default_workspace
    else:
        workspace = _ask_for_workspace(api_key=api_key)

    return workspace, True


def configure(
    api_key: Optional[str] = None,
    workspace: Optional[str] = None,
    url: Optional[str] = None,
    use_local: bool = False,
    force: bool = False,
) -> None:
    """
    Create a local configuration file for the Python SDK. If a configuration file already exists,
    it will not be overwritten unless the `force` parameter is set to True.

    Args:
        api_key: The API key if using an Opik Cloud.
        workspace: The workspace name if using an Opik Cloud.
        url: The URL of the Opik instance if you are using a local deployment.
        use_local: Whether to use a local deployment.
        force: If true, the configuration file will be recreated and existing settings will be overwritten.

    Raises:
        ConfigurationError
    """

    # OPIK CLOUD
    if use_local is False:
        _configure_cloud(
            api_key=api_key,
            workspace=workspace,
            force=force,
        )
        return

    # LOCAL OPIK DEPLOYMENT
    _configure_local(url=url, force=force)
    return


def _configure_cloud(
    api_key: Optional[str],
    workspace: Optional[str],
    force: bool = False,
) -> None:
    """
    Configure the cloud Opik instance by handling API key and workspace settings.

    Args:
        api_key (Optional[str]): The API key for the Opik Cloud.
        workspace (Optional[str]): The workspace name for the Opik Cloud.
        force (bool): If True, forces reconfiguration by overwriting the existing settings.
    """
    current_config = opik.config.OpikConfig()

    # TODO: Update the is_interactive() check, today always returns True so commented the code below
    # # first check parameters.
    # if is_interactive() is False and api_key is None and current_config.api_key is None:
    #     raise ConfigurationError("No API key provided for cloud Opik instance.")

    # if (
    #     is_interactive() is False
    #     and workspace is None
    #     and current_config.workspace is None
    # ):
    #     raise ConfigurationError("No workspace name provided for cloud Opik instance.")

    # Handle API key: get or prompt for one if needed
    api_key, update_config_with_api_key = _get_api_key(
        api_key=api_key,
        current_config=current_config,
        force=force,
    )

    # Handle workspace: get or prompt for one if needed
    workspace, update_config_with_workspace = _get_workspace(
        workspace=workspace,
        api_key=api_key,
        current_config=current_config,
        force=force,
    )

    # Update configuration if either API key or workspace has changed
    if update_config_with_api_key or update_config_with_workspace:
        _update_config(
            api_key=api_key,
            url=OPIK_BASE_URL_CLOUD,
            workspace=workspace,
        )
    else:
        LOGGER.info(
            "Opik is already configured. You can check the settings by viewing the config file at %s",
            current_config.config_file_fullpath,
        )


def _configure_local(url: Optional[str], force: bool = False) -> None:
    """
    Configure the local Opik instance by setting the local URL and workspace.

    Args:
        url (Optional[str]): The URL of the local Opik instance.
        force (bool): Whether to force the configuration even if local settings exist.

    Raises:
        ConfigurationError: Raised if the Opik instance is not active or not found.
    """
    # TODO: this needs to be refactored - _login_local might only need url from the outside.
    # But we still have to init api_key and workspace because they are required in order to update config
    api_key = None
    workspace = OPIK_WORKSPACE_DEFAULT_NAME
    current_config = opik.config.OpikConfig()

    # Step 1: If the URL is provided and active, update the configuration
    if url is not None and is_instance_active(url):
        _update_config(
            api_key=api_key,
            url=url,
            workspace=workspace,
        )
        return

    # Step 2: Check if the default local instance is active
    if is_instance_active(OPIK_BASE_URL_LOCAL):
        if not force and current_config.url_override == OPIK_BASE_URL_LOCAL:
            LOGGER.info(
                f"Opik is already configured to local instance at {OPIK_BASE_URL_LOCAL}."
            )
            return

        # Step 4: Ask user if they want to use the found local instance
        use_url = ask_user_for_approval(
            f"Found local Opik instance on: {OPIK_BASE_URL_LOCAL}, do you want to use it? (Y/n)"
        )

        if use_url:
            _update_config(
                api_key=api_key,
                url=OPIK_BASE_URL_LOCAL,
                workspace=workspace,
            )
            return

    # Step 5: Ask user for URL if no valid local instance is found or approved
    user_input_url = _ask_for_url()
    _update_config(
        api_key=api_key,
        url=user_input_url,
        workspace=workspace,
    )
