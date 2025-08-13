import getpass
import logging
import os
from typing import Final, Optional

import httpx
import opik.config
import urllib.parse
from opik.api_objects.opik_client import get_client_cached
from opik.config import (
    OPIK_WORKSPACE_DEFAULT_NAME,
)
from opik.configurator.interactive_helpers import ask_user_for_approval, is_interactive
from opik.configurator import opik_rest_helpers
from opik.exceptions import ConfigurationError
import opik.url_helpers as url_helpers
from opik.api_key import opik_api_key


LOGGER = logging.getLogger(__name__)

OPIK_BASE_URL_CLOUD: Final[str] = "https://www.comet.com/"
OPIK_BASE_URL_LOCAL: Final[str] = "http://localhost:5173/"


class OpikConfigurator:
    def __init__(
        self,
        api_key: Optional[str] = None,
        workspace: Optional[str] = None,
        url: Optional[str] = None,
        use_local: bool = False,
        force: bool = False,
        self_hosted_comet: bool = False,
        automatic_approvals: bool = False,
    ):
        self.api_key = api_key
        self.workspace = workspace
        self.use_local = use_local
        self.force = force
        self.current_config = opik.config.OpikConfig()
        self.self_hosted_comet = self_hosted_comet
        self.automatic_approvals = automatic_approvals

        # Handle URL
        #
        # This URL set here might not be the final one.
        # It's possible that the URL will be extracted from the smart api key on the later stage.
        # In that case `self.base_url` field will be updated.
        if url is None:
            self.base_url = (
                OPIK_BASE_URL_LOCAL if self.use_local else OPIK_BASE_URL_CLOUD
            )
        else:
            self.base_url = url_helpers.get_base_url(url)

    def configure(self) -> None:
        """
        Create a local configuration file for the Python SDK. If a configuration file already exists,
        it will not be overwritten unless the `force` parameter is set to True.

        Raises:
            ConfigurationError
            ConnectionError
        """

        # if there is already cached Opik client instance
        if get_client_cached.cache_info().currsize > 0:
            LOGGER.info(
                'Existing Opik clients will not use updated values for "url", "api_key", "workspace".'
            )

        # OPIK CLOUD
        if self.use_local is False:
            self._configure_cloud()
            return

        # LOCAL OPIK DEPLOYMENT
        self._configure_local()
        return

    def _configure_cloud(self) -> None:
        """
        Configure the non-local Opik instance by handling API key and workspace settings.
        Non-local means both cloud and onprem.
        """
        # Handle API key: get or prompt for one if needed
        update_config_with_api_key = self._set_api_key()

        # Handle workspace: get or prompt for one if needed
        update_config_with_workspace = self._set_workspace()

        # Update configuration if either API key or workspace has changed
        if update_config_with_api_key or update_config_with_workspace:
            self._update_config()
        else:
            self._update_config(save_to_file=False)
            _set_environment_variables_for_integrations(
                api_key=self.api_key, workspace=self.workspace
            )
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
        url_was_provided = not (self.base_url == OPIK_BASE_URL_CLOUD)
        if not url_was_provided:
            self.base_url = OPIK_BASE_URL_LOCAL

        # Step 1: If the URL is provided and active, update the configuration
        if url_was_provided and opik_rest_helpers.is_instance_active(self.base_url):
            self._update_config(save_to_file=self.force)
            return

        # Step 2: Check if the default local instance is active
        if opik_rest_helpers.is_instance_active(OPIK_BASE_URL_LOCAL):
            if (
                not self.force
                and self.current_config.url_override == OPIK_BASE_URL_LOCAL
            ):
                LOGGER.info(
                    f"Opik is already configured to local instance at {OPIK_BASE_URL_LOCAL}."
                )
                return

            # Step 3: Ask user if they want to use the found local instance
            if not is_interactive() and not self.automatic_approvals:
                raise ConfigurationError(
                    f"Opik URL is not specified - A local Opik instance was detected at {OPIK_BASE_URL_LOCAL}, to use it set your URL using the environment variable OPIK_URL_OVERRIDE or provide it as an argument. For more details, refer to the documentation: https://www.comet.com/docs/opik/tracing/sdk_configuration."
                )

            use_url = (
                True
                if self.automatic_approvals
                else ask_user_for_approval(
                    f"Found local Opik instance on: {OPIK_BASE_URL_LOCAL}, do you want to use it? (Y/n)"
                )
            )

            if use_url:
                self.base_url = OPIK_BASE_URL_LOCAL
                self._update_config()
                return

        # Step 4: Ask user for URL if no valid local instance is found or approved
        if not is_interactive():
            raise ConfigurationError(
                "Opik URL is not specified - Please set your Opik instance URL using the environment variable OPIK_URL_OVERRIDE or provide it as an argument. For more details, refer to the documentation: https://www.comet.com/docs/opik/tracing/sdk_configuration."
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
            extracted_base_url = _extract_base_url_from_api_key(self.api_key)
            if extracted_base_url is None and self.self_hosted_comet:
                self._ask_for_url()

            if not opik_rest_helpers.is_api_key_correct(
                self.api_key, url=extracted_base_url or self.base_url
            ):
                raise ConfigurationError("API key is incorrect.")
            self._try_set_url_from_api_key()
            config_file_needs_updating = True if self.force else False

        elif self.force and self.api_key is None:
            self._ask_for_api_key()
            self._try_set_url_from_api_key()
            config_file_needs_updating = True

        elif self.api_key is None and self.current_config.api_key is None:
            self._ask_for_api_key()
            self._try_set_url_from_api_key()
            config_file_needs_updating = True

        elif self.api_key is None and self.current_config.api_key is not None:
            self.api_key = self.current_config.api_key
            self._try_set_url_from_api_key()

        return config_file_needs_updating

    def _ask_for_api_key(self) -> None:
        """
        Prompt the user for an Opik API key and verify its validity.
        Verification is done against:
            * cloud, if no url was provided, self_hosted_comet is false
            * provided url (if exists)
            * url, extracted from the smart API key
        The function retries up to 3 times if the API key is invalid.

        Raises:
            ConfigurationError: Raised if the API key provided by the user is invalid after 3 attempts.
        """
        retries = 3

        settings_url = urllib.parse.urljoin(
            url_helpers.get_base_url(self.base_url), "/api/my/settings/"
        )

        url_was_not_passed = self.base_url == OPIK_BASE_URL_CLOUD
        if not self.self_hosted_comet:
            if url_was_not_passed:
                LOGGER.info(
                    "Your Opik API key is available in your account settings, can be found at %s for Opik cloud",
                    settings_url,
                )
            else:
                LOGGER.info(
                    "Your Opik API key is available in your account settings, can be found at %s",
                    settings_url,
                )

        if not is_interactive():
            raise ConfigurationError(
                "API key missing - Please set your API key using the environment variable OPIK_API_KEY or provide it as an argument. For more details, refer to the documentation: https://www.comet.com/docs/opik/tracing/sdk_configuration."
            )

        while retries > 0:
            user_input_api_key = getpass.getpass("Please enter your Opik API key:")

            extracted_base_url = _extract_base_url_from_api_key(user_input_api_key)
            if extracted_base_url is None and self.self_hosted_comet:
                self._ask_for_url()

            current_iteration_url = extracted_base_url or self.base_url

            if opik_rest_helpers.is_api_key_correct(
                user_input_api_key,
                url=current_iteration_url,
            ):
                self.api_key = user_input_api_key
                return
            else:
                LOGGER.error(
                    f"The API key provided is not valid on {current_iteration_url}. Please try again."
                )
                retries -= 1
        raise ConfigurationError("API key is incorrect.")

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
            if not opik_rest_helpers.is_workspace_name_correct(
                api_key=self.api_key, workspace=self.workspace, url=self.base_url
            ):
                raise ConfigurationError(
                    f"Workspace `{self.workspace}` is incorrect for the given API key."
                )
            return True if self.force else False

        # Case 2: Use workspace from the current configuration if not forced to change
        if (
            "workspace" in self.current_config.model_fields_set
            and self.current_config.workspace != OPIK_WORKSPACE_DEFAULT_NAME
            and not self.force
        ):
            self.workspace = self.current_config.workspace
            return False

        # Case 3: No workspace provided, prompt the user
        default_workspace = self._get_default_workspace()
        use_default_workspace = (
            True
            if self.automatic_approvals
            else ask_user_for_approval(
                f'Do you want to use "{default_workspace}" workspace? (Y/n)'
            )
        )

        if use_default_workspace:
            self.workspace = default_workspace
        else:
            self._ask_for_workspace()

        return True

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
                response = client.get(
                    url=url_helpers.get_account_details_url(self.base_url)
                )

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
                "Workspace name missing - Please set your workspace name using the environment variable OPIK_WORKSPACE or provide it as an argument. For more details, refer to the documentation: https://www.comet.com/docs/opik/tracing/sdk_configuration."
            )

        while retries > 0:
            user_input_workspace = input(
                "Please enter your Opik instance workspace name: "
            )
            if opik_rest_helpers.is_workspace_name_correct(
                api_key=self.api_key, workspace=user_input_workspace, url=self.base_url
            ):
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
            # Prototype
            url = (
                urllib.parse.urljoin(self.base_url, "opik/api/")
                if not self.use_local
                else urllib.parse.urljoin(self.base_url, "/api/")
            )

            if save_to_file:
                new_config = opik.config.OpikConfig(
                    api_key=self.api_key,
                    url_override=url,
                    workspace=self.workspace,
                )
                new_config.save_to_file()

            # Update current session configuration
            opik.config.update_session_config("api_key", self.api_key)

            opik.config.update_session_config("url_override", url)
            opik.config.update_session_config("workspace", self.workspace)
        except Exception as e:
            LOGGER.error(f"Failed to update config: {str(e)}")
            raise ConfigurationError("Failed to update configuration.")

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

            if user_input_opik_url == "":
                raise ConfigurationError(
                    "URL cannot be empty. Please enter a valid URL. For more details, refer to the documentation: https://www.comet.com/docs/opik/tracing/sdk_configuration."
                )

            user_input_opik_url = url_helpers.get_base_url(user_input_opik_url)

            if opik_rest_helpers.is_instance_active(user_input_opik_url):
                self.base_url = user_input_opik_url
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

    def _try_set_url_from_api_key(self) -> None:
        assert self.api_key is not None
        extracted_base_url = _extract_base_url_from_api_key(self.api_key)

        if extracted_base_url is None:
            return

        if (
            extracted_base_url != url_helpers.get_base_url(self.base_url)
            and self.base_url != OPIK_BASE_URL_CLOUD
        ):
            LOGGER.warning(
                "The url provided in the configure (%s) method doesn't match the domain linked to the API key provided and will be ignored",
                self.base_url,
            )

        self.base_url = extracted_base_url


def _set_environment_variables_for_integrations(
    api_key: Optional[str], workspace: Optional[str]
) -> None:
    """
    Environment variables are set for use by some integrations (liteLLM, etc.) when both the API key and workspace name
    are provided by the user. According to the current implementation logic, these values will not be
    saved to the OPIK configuration file. As a result, some third-party integrations will not be able to use them.
    This is a workaround for this issue: https://github.com/comet-ml/opik/issues/2118
    """
    if api_key is not None:
        os.environ["OPIK_API_KEY"] = api_key
    if workspace is not None:
        os.environ["OPIK_WORKSPACE"] = workspace


def _extract_base_url_from_api_key(api_key: str) -> Optional[str]:
    opik_api_key_ = opik_api_key.parse_api_key(api_key)

    if opik_api_key_ is not None and opik_api_key_.base_url is not None:
        return opik_api_key_.base_url

    return None


def configure(
    api_key: Optional[str] = None,
    workspace: Optional[str] = None,
    url: Optional[str] = None,
    use_local: bool = False,
    force: bool = False,
    automatic_approvals: bool = False,
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
        automatic_approvals: if True, `yes` will automatically be answered whenever a user approval is required

    Raises:
        ConfigurationError
    """
    client = OpikConfigurator(
        api_key=api_key,
        workspace=workspace,
        url=url,
        use_local=use_local,
        force=force,
        automatic_approvals=automatic_approvals,
    )
    client.configure()
