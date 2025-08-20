from __future__ import annotations
import configparser
import logging
import os
import sys
import pathlib
import urllib.parse
from typing import Any, Dict, Final, List, Literal, Optional, Tuple, Type, Union

import opik.decorator.tracing_runtime_config as tracing_runtime_config
import pydantic
import pydantic_settings
from pydantic_settings import BaseSettings, InitSettingsSource
from pydantic_settings.sources import ConfigFileSourceMixin

from . import dict_utils, url_helpers
from .api_key import opik_api_key

PathType = Union[
    pathlib.Path,
    str,
    List[Union[pathlib.Path, str]],
    Tuple[Union[pathlib.Path, str], ...],
]

_SESSION_CACHE_DICT: Dict[str, Any] = {}

MAX_BATCH_SIZE_MB = 5

OPIK_URL_CLOUD: Final[str] = "https://www.comet.com/opik/api/"
OPIK_URL_LOCAL: Final[str] = "http://localhost:5173/api/"

OPIK_PROJECT_DEFAULT_NAME: Final[str] = "Default Project"
OPIK_WORKSPACE_DEFAULT_NAME: Final[str] = "default"

CONFIG_FILE_PATH_DEFAULT: Final[str] = "~/.opik.config"

LOGGER = logging.getLogger(__name__)


class IniConfigSettingsSource(InitSettingsSource, ConfigFileSourceMixin):
    """
    A source class that loads variables from a INI file
    """

    def __init__(
        self,
        settings_cls: Type[BaseSettings],
    ):
        config_file_path = os.getenv("OPIK_CONFIG_PATH", CONFIG_FILE_PATH_DEFAULT)
        expanded_path = pathlib.Path(config_file_path).expanduser()
        if config_file_path != CONFIG_FILE_PATH_DEFAULT and not expanded_path.exists():
            LOGGER.warning(
                f"Config file not found at the path '{expanded_path}' provided by the `OPIK_CONFIG_PATH` environment variable."
            )
        self.ini_data = self._read_files(expanded_path)

        super().__init__(settings_cls, self.ini_data)

    def _read_file(self, file_path: pathlib.Path) -> Dict[str, Any]:
        config = configparser.ConfigParser()
        config.read(file_path)
        config_values = {
            section: dict(config.items(section)) for section in config.sections()
        }

        if "opik" in config_values:
            return config_values["opik"]

        return {}


class OpikConfig(pydantic_settings.BaseSettings):
    """
    Initializes every configuration variable with the first
    found value. The order of sources used:
    1. User passed values
    2. Session config dict (can be populated by calling `update_session_config(...)`)
    3. Environment variables (they must start with "OPIK_" prefix)
    4. Load from file
    5. Default values
    """

    model_config = pydantic_settings.SettingsConfigDict(env_prefix="opik_")

    @classmethod
    def settings_customise_sources(
        cls,
        settings_cls: Type[pydantic_settings.BaseSettings],
        init_settings: pydantic_settings.PydanticBaseSettingsSource,
        env_settings: pydantic_settings.PydanticBaseSettingsSource,
        dotenv_settings: pydantic_settings.PydanticBaseSettingsSource,
        file_secret_settings: pydantic_settings.PydanticBaseSettingsSource,
    ) -> Tuple[pydantic_settings.PydanticBaseSettingsSource, ...]:
        return (
            init_settings,
            pydantic_settings.InitSettingsSource(
                pydantic_settings.BaseSettings, _SESSION_CACHE_DICT
            ),
            env_settings,
            IniConfigSettingsSource(settings_cls=cls),
        )

    # Below are Opik configurations

    url_override: str = OPIK_URL_CLOUD
    """Opik backend base URL"""

    project_name: str = OPIK_PROJECT_DEFAULT_NAME
    """Opik project name"""

    workspace: str = OPIK_WORKSPACE_DEFAULT_NAME
    """Opik workspace"""

    api_key: Optional[str] = None
    """Opik API key. This is not required if you are running against open source Opik installation"""

    default_flush_timeout: Optional[int] = None
    """
    Maximum time to wait when flushing Opik messages queues (in seconds).
    In particular waiting happens when calling:
    * Opik().flush()
    * Opik().end()
    * flush_tracker()
    And when the process is ending.

    If it's not set - there is no timeout.
    """

    background_workers: int = 4
    """
    The amount of background threads that submit data to the backend.
    """

    file_upload_background_workers: int = 16
    """
    The amount of background threads that upload files to the backend.
    """

    console_logging_level: Literal["DEBUG", "INFO", "WARNING", "ERROR", "CRITICAL"] = (
        "INFO"
    )
    """
    Logging level for console logs.
    """

    file_logging_level: Optional[
        Literal["DEBUG", "INFO", "WARNING", "ERROR", "CRITICAL"]
    ] = None
    """
    Logging level for file logs. Is not configured - nothing is logged to the file.
    """

    logging_file: str = "opik.log"
    """
    File to write the logs to.
    """

    pytest_experiment_enabled: bool = True
    """
    If enabled, tests decorated with `llm_unit` will log data to Opik experiments
    """

    check_tls_certificate: bool = True
    """
    If enabled, TLS verification is enabled for all HTTP requests.
    """

    track_disable: bool = False
    """
    If set to True, then `@track` decorator and `track_LIBRARY(...)` integrations do not log any data.
    Any other API will continue working.

    This setting can be overridden at runtime using:
    - opik.set_tracing_active(False)  # Disable tracing
    - opik.set_tracing_active(True)   # Enable tracing
    - opik.is_tracing_active()        # Check current state
    - opik.reset_tracing_to_config_default()  # Reset to this config value

    Runtime overrides take precedence over this static configuration.

    We do not recommend disable tracking unless you only use tracking functionalities in your project because
    it might lead to unexpected results for the features that rely on spans/traces created.
    """

    sentry_enable: bool = True
    """
    If set to True, Opik will send the information about the errors to Sentry.
    """

    sentry_dsn: str = "https://34bd6f9621ca2783be63f320e35de0dc@o168229.ingest.us.sentry.io/4508620148441088"  # 24.07.2025
    """
    Sentry project DSN which is used as a destination for sentry events.
    In case there is a need to update reporting rules and stop receiving events from existing users,
    current DSN should disabled in Sentry project settings, a new DSN should be created and placed here
    instead of the old one.
    """

    enable_litellm_models_monitoring: bool = True
    """
    If set to True - Opik will create llm spans for LiteLLMChatModel calls.
    It is mainly to be used in tests since litellm uses external Opik callback
    which makes HTTP requests not via the opik package.
    """

    enable_json_request_compression: bool = True
    """
    If set to True - Opik will compress the JSON request body.
    """

    guardrail_timeout: int = 30
    """
    Timeout for guardrail.validate calls in seconds. If response takes more than this, it will be considered failed and raises an Exception.
    """

    maximal_queue_size: int = 100_000
    """
    Specifies the maximum number of messages that can be queued for delivery when a connection error occurs or rate limiting is in effect.
    """
    maximal_queue_size_batch_factor: int = 10
    """
    Defines the factor applied to the `maximal_queue_size` to reduce the maximal message queue size when batching is enabled.
    """

    log_start_trace_span: bool = True
    """
    If set to True, both the start and end of the trace and span will be logged. This is useful for traces and spans that span long durations.
    For shorter traces/spans, it is recommended to keep this setting disabled to minimize data logging overhead.
    """

    @property
    def config_file_fullpath(self) -> pathlib.Path:
        config_file_path = os.getenv("OPIK_CONFIG_PATH", CONFIG_FILE_PATH_DEFAULT)
        return pathlib.Path(config_file_path).expanduser()

    @property
    def config_file_exists(self) -> bool:
        """
        Determines whether the configuration file exists at the specified path.
        """
        return self.config_file_fullpath.exists()

    @property
    def is_cloud_installation(self) -> bool:
        """
        Determine if the installation type is a cloud installation.
        """
        return url_helpers.get_base_url(self.url_override) == url_helpers.get_base_url(
            OPIK_URL_CLOUD
        )

    @property
    def is_localhost_installation(self) -> bool:
        return "localhost" in self.url_override

    @property
    def guardrails_backend_host(self) -> str:
        return url_helpers.get_base_url(self.url_override) + "guardrails/"

    @property
    def runtime(self) -> tracing_runtime_config.TracingRuntimeConfig:
        return tracing_runtime_config.runtime_cfg

    @pydantic.model_validator(mode="after")
    def _set_url_override_from_api_key(self) -> "OpikConfig":
        url_was_not_provided = (
            "url_override" not in self.model_fields_set or self.url_override is None
        )
        url_needs_configuration = self.api_key is not None and url_was_not_provided

        if not url_needs_configuration:
            return self

        assert self.api_key is not None
        opik_api_key_ = opik_api_key.parse_api_key(self.api_key)

        if opik_api_key_ is not None and opik_api_key_.base_url is not None:
            self.url_override = urllib.parse.urljoin(
                opik_api_key_.base_url, "opik/api/"
            )

        return self

    def save_to_file(self) -> None:
        """
        Save configuration to a file

        Raises:
            OSError: If there is an issue writing to the file.
        """
        config_file_content = configparser.ConfigParser()

        config_file_content["opik"] = {
            "url_override": self.url_override,
            "workspace": self.workspace,
        }

        if self.api_key is not None:
            config_file_content["opik"]["api_key"] = self.api_key

        try:
            with open(
                self.config_file_fullpath, mode="w+", encoding="utf-8"
            ) as config_file:
                config_file_content.write(config_file)
            LOGGER.info(f"Configuration saved to file: {self.config_file_fullpath}")
        except OSError as e:
            LOGGER.error(f"Failed to save configuration: {e}")
            raise

    def as_dict(self, mask_api_key: bool) -> Dict[str, Any]:
        """
        Retrieves the current configuration with the API key value masked.
        """
        current_values = self.model_dump()
        if current_values.get("api_key") is not None and mask_api_key:
            current_values["api_key"] = "*** HIDDEN ***"
        return current_values

    def check_for_known_misconfigurations(
        self, show_misconfiguration_message: bool = False
    ) -> bool:
        """
        Attempts to detects if Opik is misconfigured and optionally displays
        a corresponding error message.
        Works only for Opik cloud and OSS localhost installations.

        Parameters:
        show_misconfiguration_message : A flag indicating whether to display detailed error messages if the configuration
            is determined to be misconfigured. Defaults to False.
        """
        if "pytest" in sys.modules:
            return False

        is_misconfigured_flag, error_message = (
            self.get_misconfiguration_detection_results()
        )

        if is_misconfigured_flag:
            if show_misconfiguration_message:
                print()
                LOGGER.error(
                    "========================\n"
                    f"{error_message}\n"
                    "==============================\n"
                )
            return True

        return False

    def get_misconfiguration_detection_results(self) -> Tuple[bool, Optional[str]]:
        """
        Tries detecting misconfigurations for either cloud or localhost environments.
        The detection will not work for any other kind of installation.

        Returns:
            Tuple[bool, Optional[str]]: A tuple where the first element indicates
            whether the configuration is misconfigured (True for misconfigured, False for valid).
            The second element is an optional string that contains
            an error message if there is a configuration issue, or None if the
            configuration is valid.
        """
        is_misconfigured_for_cloud_flag, error_message = (
            self._is_misconfigured_for_cloud()
        )
        if is_misconfigured_for_cloud_flag:
            return True, error_message

        is_misconfigured_for_localhost_flag, error_message = (
            self._is_misconfigured_for_localhost()
        )
        if is_misconfigured_for_localhost_flag:
            return True, error_message

        return False, None

    def _is_misconfigured_for_cloud(self) -> Tuple[bool, Optional[str]]:
        """
        Determines if the current Opik configuration is misconfigured for cloud logging.

        Returns:
            Tuple[bool, Optional[str]]: A tuple where the first element is a boolean indicating if
            the configuration is misconfigured for cloud logging, and the second element is either
            an error message indicating the reason for misconfiguration or None.
        """
        api_key_configured = self.api_key is not None
        tracking_disabled = self.track_disable

        if (
            self.is_cloud_installation
            and (not api_key_configured)
            and (not tracking_disabled)
        ):
            error_message = (
                "The API key must be specified to log data to https://www.comet.com/opik.\n"
                "You can use `opik configure` CLI command to configure your environment for logging.\n"
                "See the configuration details in the docs: https://www.comet.com/docs/opik/tracing/sdk_configuration.\n"
            )
            return True, error_message

        return False, None

    def _is_misconfigured_for_localhost(self) -> Tuple[bool, Optional[str]]:
        """
        Determines if the current setup is misconfigured for a local open-source installation.

        Returns:
            Tuple[bool, Optional[str]]: A tuple where the first element is a boolean indicating if
            the configuration is misconfigured for local logging, and the second element is either
            an error message indicating the reason for misconfiguration or None.
        """

        workspace_is_default = self.workspace == OPIK_WORKSPACE_DEFAULT_NAME
        tracking_disabled = self.track_disable

        if (
            self.is_localhost_installation
            and (not workspace_is_default)
            and (not tracking_disabled)
        ):
            error_message = (
                "Open source installations do not support workspace specification. Only `default` is available.\n"
                "See the configuration details in the docs: https://www.comet.com/docs/opik/tracing/sdk_configuration\n"
                "If you need advanced workspace management - you may consider using our cloud offer (https://www.comet.com/site/pricing/)\n"
                "or contact our team for purchasing and setting up a self-hosted installation.\n"
            )
            return True, error_message

        return False, None


def update_session_config(key: str, value: Any) -> None:
    _SESSION_CACHE_DICT[key] = value


def get_from_user_inputs(**user_inputs: Any) -> OpikConfig:
    """
    Instantiates an OpikConfig using provided user inputs.
    """
    cleaned_user_inputs = dict_utils.remove_none_from_dict(user_inputs)

    return OpikConfig(**cleaned_user_inputs)
