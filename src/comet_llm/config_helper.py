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


import copy
import logging
from typing import Any, Dict


def _muted_import_comet_ml() -> None:
    try:
        logging.disable(logging.CRITICAL)
        import comet_ml
        import comet_ml.config
        import comet_ml.config_class
    finally:
        pass
        logging.disable(0)


_muted_import_comet_ml()  # avoid logger warnings on import

import comet_ml.config as comet_ml_config
from comet_ml.config import Config, create_config_from_map

LOGGER = logging.getLogger(__name__)

DEFAULT_COMET_BASE_URL = "https://www.comet.com"


def _extended_comet_ml_config_map() -> Dict[str, Dict[str, Any]]:
    CONFIG_MAP_EXTENSION = {
        "comet.disable": {"type": int, "default": 0},
        "comet.logging.console": {"type": str, "default": "INFO"},
        "comet.raise_exceptions_on_error": {"type": int, "default": 0},
        "comet.internal.check_tls_certificate": {"type": bool, "default": True},
        "comet.online": {"type": bool, "default": True},
        "comet.offline_directory": {"type": str, "default": ".cometllm-runs"},
        "comet.offline_batch_duration_seconds": {"type": int, "default": 300},
    }

    COMET_LLM_CONFIG_MAP: Dict[str, Dict[str, Any]] = copy.deepcopy(
        comet_ml_config.CONFIG_MAP
    )
    COMET_LLM_CONFIG_MAP.update(CONFIG_MAP_EXTENSION)

    return COMET_LLM_CONFIG_MAP


def create_config_instance() -> "Config":
    COMET_LLM_CONFIG_MAP = _extended_comet_ml_config_map()
    config_instance = create_config_from_map(COMET_LLM_CONFIG_MAP)

    return config_instance
