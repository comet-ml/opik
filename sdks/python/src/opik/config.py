import configparser
import logging
import os
import pathlib
import urllib.parse
from typing import Any, Dict, Final, List, Literal, Optional, Tuple, Type, Union

import pydantic
import pydantic_settings
from pydantic_settings import BaseSettings, InitSettingsSource
from pydantic_settings.sources import ConfigFileSourceMixin

from . import dict_utils
from .api_key import opik_api_key

PathType = Union[
    pathlib.Path,
    str,
    List[Union[pathlib.Path, str]],
    Tuple[Union[pathlib.Path, str], ...],
]

_SESSION_CACHE_DICT: Dict[str, Any] = {}

OPIK_URL_CLOUD: Final[str] = "https://www.comet.com/opik/api"
OPIK_URL_LOCAL: Final[str] = "http://localhost:5173/api"

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

    background_workers: int = 8
    """
    The amount of background threads that submit data to the backend.
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

    @property
    def config_file_fullpath(self) -> pathlib.Path:
        config_file_path = os.getenv("OPIK_CONFIG_PATH", CONFIG_FILE_PATH_DEFAULT)
        return pathlib.Path(config_file_path).expanduser()

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


def update_session_config(key: str, value: Any) -> None:
    _SESSION_CACHE_DICT[key] = value


def get_from_user_inputs(**user_inputs: Any) -> OpikConfig:
    """
    Instantiates an OpikConfig using provided user inputs.
    """
    cleaned_user_inputs = dict_utils.remove_none_from_dict(user_inputs)

    return OpikConfig(**cleaned_user_inputs)
