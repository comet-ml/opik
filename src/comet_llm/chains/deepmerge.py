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

from comet_llm import logging_messages

LOGGER = logging.getLogger(__name__)


def deepmerge(
    dict1: Dict[str, Any], dict2: Dict[str, Any], max_depth: int = 10
) -> Dict[str, Any]:
    merged = copy.deepcopy(dict1)

    for key, value in dict2.items():
        if (
            key in merged
            and _is_dict(merged[key])
            and _is_dict(value)
            and max_depth > 0
        ):
            merged[key] = deepmerge(merged[key], value, max_depth=max_depth - 1)
        else:
            if key in merged:
                LOGGER.debug(
                    logging_messages.METADATA_KEY_COLLISION_DURING_DEEPMERGE,
                    key,
                    merged[key],
                    value,
                )
            merged[key] = value

    return merged


def _is_dict(item: Any) -> bool:
    return isinstance(item, dict)
