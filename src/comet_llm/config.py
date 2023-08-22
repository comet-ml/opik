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


def workspace() -> Optional[str]:
    return comet_ml.get_config("comet.workspace")  # type: ignore


def project_name() -> Optional[str]:
    return comet_ml.get_config("comet.project_name")  # type: ignore


def comet_url() -> str:
    comet_ml_config = comet_ml.get_config()
    return comet_ml.get_backend_address(comet_ml_config)  # type: ignore


def api_key() -> Optional[str]:
    comet_ml_config = comet_ml.get_config()
    api_key = comet_ml.get_api_key(None, comet_ml_config)
    return api_key  # type: ignore


def logging_available() -> bool:
    if api_key() is None:
        return False

    return True


def autologging_enabled() -> bool:
    return not comet_ml.get_config("comet.disable_auto_logging")  # type: ignore
