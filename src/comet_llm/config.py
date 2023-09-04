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

import functools
import logging
from types import ModuleType
from typing import TYPE_CHECKING, Dict, Optional


def _muted_import_comet_ml() -> ModuleType:
    try:
        logging.disable(logging.CRITICAL)
        import comet_ml
        import comet_ml.config

        return comet_ml, comet_ml.config  # type: ignore
    finally:
        pass
        logging.disable(0)


comet_ml, comet_ml_config = _muted_import_comet_ml()


def _extend_comet_ml_config():
    CONFIG_MAP_EXTENSION = {"comet.disable": {"type": int, "default": 0}}

    comet_ml_config.CONFIG_MAP.update(CONFIG_MAP_EXTENSION)


@functools.lru_cache(maxsize=1)
def _comet_ml_config() -> "comet_ml_config.Config":
    return comet_ml.get_config()


def workspace() -> Optional[str]:
    return _comet_ml_config()["comet.workspace"]  # type: ignore


def project_name() -> Optional[str]:
    return _comet_ml_config()["comet.project_name"]  # type: ignore


def comet_url() -> str:
    return comet_ml.get_backend_address(_comet_ml_config())  # type: ignore


def api_key() -> Optional[str]:
    comet_ml_config = _comet_ml_config()
    api_key = comet_ml.get_api_key(None, comet_ml_config)
    return api_key  # type: ignore


def is_ready() -> bool:
    """
    True if comet API key is set.
    """
    return api_key() is not None


def comet_disabled() -> bool:
    return bool(_comet_ml_config()["comet.disable"])


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
        api_key: str (optional) comet API key.
        workspace: str (optional) comet workspace to use for logging.
        project: str (optional) project name to create in comet workspace.

    Valid settings include:
    """

    kwargs: Dict[str, Optional[str]] = {
        "api_key": api_key,
        "workspace": workspace,
        "project_name": project,
    }

    kwargs = {key: value for key, value in kwargs.items() if value is not None}

    comet_ml.init(**kwargs)


_extend_comet_ml_config()
