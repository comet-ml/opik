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

from .. import experiment_info, logging_messages


@dataclasses.dataclass
class BaseMessage:
    experiment_info_: experiment_info.ExperimentInfo

    @classmethod
    def from_dict(
        cls, d: Dict[str, Any], api_key: Optional[str] = None
    ) -> "BaseMessage":
        experiment_info_ = experiment_info.get(
            **d["experiment_info_"],
            api_key=api_key,
            api_key_not_found_message=logging_messages.API_KEY_NOT_CONFIGURED
        )
        del d["experiment_info_"]
        return cls(experiment_info_=experiment_info_, **d)

    def to_dict(self) -> Dict[str, Any]:
        result = dataclasses.asdict(self)
        del result["experiment_info_"]["api_key"]

        return result


@dataclasses.dataclass
class PromptMessage(BaseMessage):
    prompt_asset_data: Dict[str, Any]
    duration: Optional[float]
    metadata: Optional[Dict[str, Union[str, bool, float, None]]]
    tags: Optional[List[str]]


@dataclasses.dataclass
class ChainMessage(BaseMessage):
    chain_data: Dict[str, JSONEncodable]
    duration: float
    tags: Optional[List[str]]
    metadata: Optional[Dict[str, JSONEncodable]]
    others: Dict[str, JSONEncodable]
    # 'other' - is a name of an attribute of experiment, logged via log_other
