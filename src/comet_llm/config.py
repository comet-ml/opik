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
#  This file can not be copied and/or distributed without the express
#  permission of Comet ML Inc.
# *******************************************************

import functools
import logging
from types import ModuleType
from typing import TYPE_CHECKING, Optional


def _muted_import_comet_ml() -> ModuleType:
    try:
        logging.disable(logging.CRITICAL)
        import comet_ml

        return comet_ml  # type: ignore
    finally:
        logging.disable(0)


comet_ml = _muted_import_comet_ml()

if TYPE_CHECKING:
    import comet_ml.config as comet_ml_config


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
