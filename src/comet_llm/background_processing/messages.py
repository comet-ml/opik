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

import dataclasses
from typing import Any, Dict, List, Optional, Union

from comet_llm.types import JSONEncodable

from .. import experiment_info


@dataclasses.dataclass
class BaseMessage:
    pass


class PromptMessage(BaseMessage):
    experiment_information: experiment_info.ExperimentInfo
    prompt_asset_data: Dict[str, Any]
    duration: Optional[float]
    metadata: Optional[Dict[str, Union[str, bool, float, None]]]
    tags: Optional[List[str]]


class ChainMessage(BaseMessage):
    experiment_information: experiment_info.ExperimentInfo
    tags: List[str]
    chain_data: Dict[str, JSONEncodable]
    duration: float
    metadata: Dict[str, JSONEncodable]
    others: Dict[str, JSONEncodable]
    # 'other' - is a name of an attribute of experiment, logged via log_other
