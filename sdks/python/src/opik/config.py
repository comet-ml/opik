import configparser
import logging
import os
import pathlib
from typing import Any, Dict, Final, Literal, Optional, Tuple, Type

import pydantic_settings

from . import dict_utils

_SESSION_CACHE_DICT: Dict[str, Any] = {}

OPIK_BASE_URL_CLOUD: Final[str] = "https://www.comet.com/opik/api"
OPIK_BASE_URL_LOCAL: Final[str] = "http://localhost:5173/api"

OPIK_PROJECT_DEFAULT_NAME: Final[str] = "Default Project"
OPIK_WORKSPACE_DEFAULT_NAME: Final[str] = "default"

OPIK_CONFIG_PATH: Final[str] = '~/.opik.config'
OPIK_CONFIG_ENV_VAR: Final[str] = 'OPIK_CONFIG_PATH'

LOGGER = logging.getLogger(__name__)


class OpikConfig(pydantic_settings.BaseSettings):
    """
    Initializes every configuration variable with the first
    found value. The order of sources used:
    1. User passed values
    2. Session config dict (can be populated by calling `update_session_config(...)`)
    3. Environment variables (they must start with "OPIK_" prefix)
    # todo load from file
    4. Default values
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
        )

    # Below are Opik configurations

    url_override: str = OPIK_BASE_URL_LOCAL
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

    def save_to_file(self) -> None:
        """
        Save configuration to a file
        """

        config_file_path = os.environ.get(OPIK_CONFIG_ENV_VAR, OPIK_CONFIG_PATH)

        # if location is a home directory
        if config_file_path.startswith("~/"):
            config_file_path = pathlib.Path.home() / config_file_path.removeprefix("~/")

        config_file_content = configparser.ConfigParser()
        config_file_content["opik"] = {
            "url": self.url_override,
            "api_key": self.api_key or "",
            "workspace": self.workspace,
        }

        LOGGER.info(f"*** Saving configuration to a file: {config_file_path}")

        with open(config_file_path, mode="w+", encoding="utf-8") as config_file:
            config_file_content.write(config_file)

        LOGGER.info("*** Saving configuration is complete!")

    def load_from_file(self) -> None:
        raise NotImplementedError()


def update_session_config(key: str, value: Any) -> None:
    _SESSION_CACHE_DICT[key] = value


def get_from_user_inputs(**user_inputs: Any) -> OpikConfig:
    """
    Instantiates an OpikConfig using provided user inputs.
    """
    cleaned_user_inputs = dict_utils.remove_none_from_dict(user_inputs)

    return OpikConfig(**cleaned_user_inputs)
