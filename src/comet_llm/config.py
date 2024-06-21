# -*- coding: utf-8 -*-
# *******************************************************
#   ____                     _               _
#  / ___|___  _ __ ___   ___| |_   _ __ ___ | |
# | |   / _ \| '_ ` _ \ / _ \ __| | '_ ` _ \| |
# | |__| (_) | | | | | |  __/ |_ _| | | | | | |
#  \____\___/|_| |_| |_|\___|\__(_)_| |_| |_|_|
#
#  Sign up for free at https://www.comet.com
#  Copyright (C) 2015-2023 Comet ML INC
#  This source code is licensed under the MIT license found in the
#  LICENSE file in the root directory of this package.
# *******************************************************


import logging
from typing import Any, Dict, Optional

from . import config_helper, logging_messages, url_helpers
from .api_key import comet_api_key


def _muted_import_comet_ml() -> None:
    try:
        logging.disable(logging.CRITICAL)
        import comet_ml
    finally:
        pass
        logging.disable(0)


_muted_import_comet_ml()  # avoid logger warnings on import

import comet_ml

LOGGER = logging.getLogger(__name__)

DEFAULT_COMET_BASE_URL = "https://www.comet.com"

CometMLConfig = Any


def workspace() -> Optional[str]:
    return _COMET_LLM_CONFIG["comet.workspace"]  # type: ignore


def project_name() -> Optional[str]:
    return _COMET_LLM_CONFIG["comet.project_name"]  # type: ignore


def comet_url() -> str:
    url = _COMET_LLM_CONFIG["comet.url_override"]

    if url is None:
        return DEFAULT_COMET_BASE_URL

    return url_helpers.get_root_url(url)


def api_key() -> Optional[str]:
    api_key = comet_ml.get_api_key(None, _COMET_LLM_CONFIG)
    return api_key  # type: ignore


def setup_comet_url(api_key: str) -> None:
    """
    If API key contains Comet URL which does not conflict with
    COMET_URL_OVERRIDE variable set before, the value from API key
    will be saved in config.
    """

    parsed_api_key = comet_api_key.parse_api_key(api_key)

    if parsed_api_key is None:
        return

    config_url_override = _COMET_LLM_CONFIG[
        "comet.url_override"
    ]  # check if we need a getter here

    if config_url_override is not None and config_url_override != "":
        config_base_url = url_helpers.get_root_url(config_url_override)
        if (
            parsed_api_key.base_url is not None
            and parsed_api_key.base_url != config_base_url
        ):
            LOGGER.warning(
                logging_messages.BASE_URL_MISMATCH_CONFIG_API_KEY,
                config_base_url,
                parsed_api_key.base_url,
            )
        # do not change base url
        return

    if parsed_api_key.base_url is not None:
        _COMET_LLM_CONFIG["comet.url_override"] = parsed_api_key.base_url


def logging_level() -> str:
    return _COMET_LLM_CONFIG["comet.logging.console"]  # type: ignore


def is_ready() -> bool:
    """
    True if comet API key is set.
    """
    return api_key() is not None


def comet_disabled() -> bool:
    return bool(_COMET_LLM_CONFIG["comet.disable"])


def raising_enabled() -> bool:
    return bool(_COMET_LLM_CONFIG["comet.raise_exceptions_on_error"])


def logging_available() -> bool:
    if api_key() is None:
        return False

    return True


def autologging_enabled() -> bool:
    return not _COMET_LLM_CONFIG["comet.disable_auto_logging"]  # type: ignore


def tls_verification_enabled() -> bool:
    return _COMET_LLM_CONFIG["comet.internal.check_tls_certificate"]  # type: ignore


def offline_enabled() -> bool:
    return not bool(_COMET_LLM_CONFIG["comet.online"])


def offline_directory() -> str:
    return str(_COMET_LLM_CONFIG["comet.offline_directory"])


def offline_batch_duration_seconds() -> int:
    return int(_COMET_LLM_CONFIG["comet.offline_batch_duration_seconds"])


def init(
    api_key: Optional[str] = None,
    workspace: Optional[str] = None,
    project: Optional[str] = None,
) -> None:
    """
    An easy, safe, interactive way to set and save your settings.

    Will ask for your api_key if not already set. Your
    api_key will not be shown.

    Will save the config to .comet.config file.
    Default location is "~/" (home) or COMET_CONFIG, if set.

    Args:
        api_key: Comet API key.
        workspace: Comet workspace to use for logging.
        project: Project name to create in comet workspace.
    """
    kwargs: Dict[str, Optional[str]] = {
        "api_key": api_key,
        "workspace": workspace,
        "project_name": project,
    }

    kwargs = {key: value for key, value in kwargs.items() if value is not None}

    comet_ml.init(**kwargs)

    global _COMET_LLM_CONFIG
    # Recreate the Config object to re-read the config files
    _COMET_LLM_CONFIG = config_helper.create_config_instance()


_COMET_LLM_CONFIG = config_helper.create_config_instance()
