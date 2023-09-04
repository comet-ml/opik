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

from typing import Any, Dict, Optional

import flatten_dict


def chain_metadata_to_flat_parameters(
    metadata: Optional[Dict[str, Any]]
) -> Dict[str, Any]:
    metadata_parameters = (
        flatten_dict.flatten(metadata, reducer="dot") if metadata is not None else {}
    )

    result = {
        key: value for key, value in metadata_parameters.items() if value is not None
    }

    return result
