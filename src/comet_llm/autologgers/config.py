# -*- coding: utf-8 -*-
# *******************************************************
#   ____                     _               _
#  / ___|___  _ __ ___   ___| |_   _ __ ___ | |
# | |   / _ \| '_ ` _ \ / _ \ __| | '_ ` _ \| |
# | |__| (_) | | | | | |  __/ |_ _| | | | | | |
#  \____\___/|_| |_| |_|\___|\__(_)_| |_| |_|_|
#
#  Sign up for free at http://www.comet.ml
#  Copyright (C) 2015-2021 Comet ML INC
#  This file can not be copied and/or distributed without the express
#  permission of Comet ML Inc.
# *******************************************************

import functools
from typing import Optional

from comet_llm import config, experiment_info


def enabled() -> bool:
    return config.logging_available()


@functools.lru_cache(maxsize=1)
def get_experiment_info() -> Optional[experiment_info.ExperimentInfo]:
    try:
        return experiment_info.get()
    except Exception:
        return None
