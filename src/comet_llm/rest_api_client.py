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

import sys
import functools
from typing import Optional

import comet_ml
from comet_ml import connection


@functools.lru_cache(maxsize=0 if "pytest" in sys.modules else 1)
def get(api_key: Optional[str] = None) -> connection.RestApiClient:
    if api_key is None:
        comet_config = comet_ml.get_config()
        api_key = comet_ml.get_api_key(None, comet_config)

    rest_api_client = connection.get_rest_api_client(
        "v2",
        api_key=api_key,
        use_cache=False,
        headers={"X-COMET-SDK-SOURCE": "Experiment"},
    )

    return rest_api_client

