import getpass
import logging
from typing import Final, List, Optional

import httpx
import opik.config
from opik.config import (
    OPIK_BASE_URL_CLOUD,
    OPIK_BASE_URL_LOCAL,
    OPIK_WORKSPACE_DEFAULT_NAME,
)
from opik.configurator.interactive_helpers import ask_user_for_approval, is_interactive
from opik.exceptions import ConfigurationError

LOGGER = logging.getLogger(__name__)

HEALTH_CHECK_URL_POSTFIX: Final[str] = "/is-alive/ping"
HEALTH_CHECK_TIMEOUT: Final[float] = 1.0

URL_ACCOUNT_DETAILS_DEFAULT: Final[str] = (
    "https://www.comet.com/api/rest/v2/account-details"
)
URL_ACCOUNT_DETAILS_POSTFIX: Final[str] = "/rest/v2/account-details"

URL_WORKSPACE_GET_LIST_DEFAULT: Final[str] = (
    "https://www.comet.com/api/rest/v2/workspaces"
)
URL_WORKSPACE_GET_LIST_POSTFIX: Final[str] = "/rest/v2/workspaces"


class OpikConfigurator:
    def __init__(
        self,
        api_key: Optional[str] = None,
        workspace: Optional[str] = None,
        url: Optional[str] = None,
        use_local: bool = False,
        force: bool = False,
    ):
        self.api_key = api_key
        self.workspace = workspace
        self.url = url
        self.use_local = use_local
        self.force = force
        self.current_config = opik.config.OpikConfig()

    def configure(self) -> None:
        """
        Create a local configuration file for the Python SDK. If a configuration file already exists,
        it will not be overwritten unless the `force` parameter is set to True.

        Raises:
            ConfigurationError
            ConnectionError
        """

        # OPIK CLOUD
        if self.use_local is False:
            self._configure_cloud()
            return

        # LOCAL OPIK DEPLOYMENT
        self._configure_local()
        return

    def _configure_cloud(self) -> None:
        """
        Configure the cloud Opik instance by handling API key and workspace settings.
        """
        # Handle URL
        if self.url is None:
            self.url = OPIK_BASE_URL_CLOUD

        # Handle API key: get or prompt for one if needed
        update_config_with_api_key = self._set_api_key()

        # Handle workspace: get or prompt for one if needed
        update_config_with_workspace = self._set_workspace()

        # Update configuration if either API key or workspace has changed
        if update_config_with_api_key or update_config_with_workspace:
            self._update_config()
        else:
            self._update_config(save_to_file=False)
            LOGGER.info(
                "Opik is already configured. You can check the settings by viewing the config file at %s",
                self.current_config.config_file_fullpath,
            )

    def _configure_local(self) -> None:
        """
        Configure the local Opik instance by setting the local URL and workspace.

        Raises:
            ConfigurationError: Raised if the Opik instance is not active or not found.
        """
        self.api_key = None
        self.workspace = OPIK_WORKSPACE_DEFAULT_NAME

        # Step 1: If the URL is provided and active, update the configuration
        if self.url is not None and self._is_instance_active(self.url):
            self._update_config(save_to_file=self.force)
            return

        # Step 2: Check if the default local instance is active
        if self._is_instance_active(OPIK_BASE_URL_LOCAL):
            if (
                not self.force
                and self.current_config.url_override == OPIK_BASE_URL_LOCAL
            ):
                LOGGER.info(
                    f"Opik is already configured to local instance at {OPIK_BASE_URL_LOCAL}."
                )
                return

            # Step 3: Ask user if they want to use the found local instance
            if not is_interactive():
                raise ConfigurationError(
                    "Non-interactive mode detected. Unable to proceed."
                )

            use_url = ask_user_for_approval(
                f"Found local Opik instance on: {OPIK_BASE_URL_LOCAL}, do you want to use it? (Y/n)"
            )
            if use_url:
                self.url = OPIK_BASE_URL_LOCAL
                self._update_config()
                return

        # Step 4: Ask user for URL if no valid local instance is found or approved
        if not is_interactive():
            raise ConfigurationError(
                "Non-interactive mode detected. Unable to proceed as no local Opik instance was found."
            )
        self._ask_for_url()
        self._update_config()

    def _set_api_key(self) -> bool:
        """
        Determines and set the correct API key based on the current configuration, force flag, and user input.

        Returns:
            bool: a boolean indicating if the configuration file needs updating.
        """
        config_file_needs_updating = False

        if self.api_key:
            if not self._is_api_key_correct(self.api_key):
                raise ConfigurationError("API key is incorrect.")
            config_file_needs_updating = True if self.force else False

        elif self.force and self.api_key is None:
            self._ask_for_api_key()
            config_file_needs_updating = True

        elif self.api_key is None and self.current_config.api_key is None:
            self._ask_for_api_key()
            config_file_needs_updating = True

        elif self.api_key is None and self.current_config.api_key is not None:
            self.api_key = self.current_config.api_key

        return config_file_needs_updating

    def _ask_for_api_key(self) -> None:
        """
        Prompt the user for an Opik cloud API key and verify its validity.
        The function retries up to 3 times if the API key is invalid.

        Raises:
            ConfigurationError: Raised if the API key provided by the user is invalid after 3 attempts.
        """
        retries = 3

        LOGGER.info(
            "Your Opik cloud API key is available at https://www.comet.com/api/my/settings/."
        )

        if not is_interactive():
            raise ConfigurationError(
                "Non-interactive mode detected. Unable to proceed as no API key has been specified."
            )

        while retries > 0:
            user_input_api_key = getpass.getpass(
                "Please enter your Opik Cloud API key:"
            )

            if self._is_api_key_correct(user_input_api_key):
                self.api_key = user_input_api_key
                return
            else:
                LOGGER.error(
                    f"The API key provided is not valid on {OPIK_BASE_URL_CLOUD}. Please try again."
                )
                retries -= 1
        raise ConfigurationError("API key is incorrect.")

    def _is_api_key_correct(self, api_key: str) -> bool:
        """
        Validates if the provided Opik API key is correct by sending a request to the cloud API.

        Returns:
            bool: True if the API key is valid (status 200), False if the key is invalid (status 401 or 403).

        Raises:
            ConnectionError: If a network-related error occurs or the response status is neither 200, 401, nor 403.
        """

        try:
            with httpx.Client() as client:
                client.headers.update({"Authorization": f"{api_key}"})
                response = client.get(url=self._url_account_details)
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

    def _set_workspace(self) -> bool:
        """
        Determines and set the correct workspace based on current configuration, force flag, and user input.

        Returns:
            bool: a boolean indicating whether the configuration file needs updating.

        Raises:
            ConfigurationError: If the provided workspace is invalid.
        """

        # Case 1: Workspace was provided by the user and is valid
        if self.workspace is not None:
            if not self._is_workspace_name_correct(self.workspace):
                raise ConfigurationError(
                    f"Workspace `{self.workspace}` is incorrect for the given API key."
                )
            return True if self.force else False

        # Case 2: Use workspace from current configuration if not forced to change
        if (
            "workspace" in self.current_config.model_fields_set
            and self.current_config.workspace != OPIK_WORKSPACE_DEFAULT_NAME
            and not self.force
        ):
            self.workspace = self.current_config.workspace
            return False

        # Case 3: No workspace provided, prompt the user
        default_workspace = self._get_default_workspace()
        use_default_workspace = ask_user_for_approval(
            f'Do you want to use "{default_workspace}" workspace? (Y/n)'
        )

        if use_default_workspace:
            self.workspace = default_workspace
        else:
            self._ask_for_workspace()

        return True

    def _is_workspace_name_correct(self, workspace: str) -> bool:
        """
        Verifies whether the provided workspace name exists in the user's cloud Opik account.

        Args:
            workspace (str): The name of the workspace to check.

        Returns:
            bool: True if the workspace is found, False otherwise.

        Raises:
            ConnectionError: Raised if there's an issue with connecting to the Opik service, or the response is not successful.
        """
        if not self.api_key:
            raise ConfigurationError("API key must be set to check workspace name.")

        try:
            with httpx.Client() as client:
                client.headers.update({"Authorization": f"{self.api_key}"})
                response = client.get(url=self._url_get_workspace_list)
        except httpx.RequestError as e:
            # Raised for network-related errors such as timeouts
            raise ConnectionError(f"Network error: {str(e)}")
        except Exception as e:
            raise ConnectionError(f"Unexpected error occurred: {str(e)}")

        if response.status_code != 200:
            raise ConnectionError(
                f"HTTP error: {response.status_code} - {response.text}"
            )

        workspaces: List[str] = response.json().get("workspaceNames", [])
        return workspace in workspaces

    def _get_default_workspace(self) -> str:
        """
        Retrieves the default Opik workspace name associated with the given API key.

        Returns:
            str: The default workspace name.

        Raises:
            ConnectionError: If there's an error while fetching the default workspace.
        """
        if not self.api_key:
            raise ConfigurationError("API key must be set.")

        try:
            with httpx.Client() as client:
                client.headers.update({"Authorization": f"{self.api_key}"})
                response = client.get(url=self._url_account_details)

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

    def _ask_for_workspace(self) -> None:
        """
        Prompt the user for an Opik instance workspace name and verify its validity.
        The function retries up to 3 times if the workspace name is invalid.

        Raises:
            ConfigurationError: Raised if the workspace name is invalid after 3 attempts.
        """
        retries = 3

        if not self.api_key:
            raise ConfigurationError("API key must be set to check workspace name.")

        if not is_interactive():
            raise ConfigurationError(
                "Non-interactive mode detected. Unable to proceed as no workspace name has been specified."
            )

        while retries > 0:
            user_input_workspace = input(
                "Please enter your cloud Opik instance workspace name: "
            )
            if self._is_workspace_name_correct(user_input_workspace):
                self.workspace = user_input_workspace
                return
            else:
                LOGGER.error(
                    "This workspace does not exist, please enter a workspace that you have access to."
                )
                retries -= 1
        raise ConfigurationError(
            "User does not have access to the workspaces provided."
        )

    def _update_config(self, save_to_file: bool = True) -> None:
        """
        Save changes to the config file and update the current session configuration.

        Raises:
            ConfigurationError: Raised if there is an issue saving the configuration or updating the session.
        """
        try:
            if save_to_file:
                new_config = opik.config.OpikConfig(
                    api_key=self.api_key,
                    url_override=self.url,
                    workspace=self.workspace,
                )
                new_config.save_to_file()

            # Update current session configuration
            opik.config.update_session_config("api_key", self.api_key)
            opik.config.update_session_config("url_override", self.url)
            opik.config.update_session_config("workspace", self.workspace)
        except Exception as e:
            LOGGER.error(f"Failed to update config: {str(e)}")
            raise ConfigurationError("Failed to update configuration.")

    @staticmethod
    def _is_instance_active(url: str) -> bool:
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

    def _ask_for_url(self) -> None:
        """
        Prompt the user for an Opik instance URL and check if it is accessible.
        The function retries up to 3 times if the URL is not accessible.

        Raises:
            ConfigurationError: Raised if the URL provided by the user is not accessible after 3 attempts.
        """
        retries = 3
        while retries > 0:
            user_input_opik_url = input("Please enter your Opik instance URL:")
            if self._is_instance_active(user_input_opik_url):
                self.url = user_input_opik_url
                return
            else:
                LOGGER.error(
                    f"Opik is not accessible at {user_input_opik_url}. "
                    f"Please try again, the URL should follow a format similar to {OPIK_BASE_URL_LOCAL}"
                )
                retries -= 1
        raise ConfigurationError(
            "Cannot use the URL provided by the user. Opik instance is not active or not found."
        )

    @property
    def _url_account_details(self) -> str:
        if self.url is None:
            return URL_ACCOUNT_DETAILS_DEFAULT

        if self.url == OPIK_BASE_URL_CLOUD:
            return URL_ACCOUNT_DETAILS_DEFAULT

        return f"{self.url}{URL_ACCOUNT_DETAILS_POSTFIX}"

    @property
    def _url_get_workspace_list(self) -> str:
        if self.url is None:
            return URL_WORKSPACE_GET_LIST_DEFAULT

        if self.url == OPIK_BASE_URL_CLOUD:
            return URL_WORKSPACE_GET_LIST_DEFAULT

        return f"{self.url}{URL_WORKSPACE_GET_LIST_POSTFIX}"


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
        force: If true, the configuration file will be recreated and existing settings
               will be overwritten with passed parameters.

    Raises:
        ConfigurationError
    """
    client = OpikConfigurator(
        api_key=api_key,
        workspace=workspace,
        url=url,
        use_local=use_local,
        force=force,
    )
    client.configure()
