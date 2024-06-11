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
import uuid
from typing import Any, ClassVar, Dict, List, Optional, Union

from comet_llm.types import JSONEncodable

from .. import experiment_info, logging_messages


def generate_id() -> str:
    return uuid.uuid4().hex


@dataclasses.dataclass
class BaseMessage:
    experiment_info_: experiment_info.ExperimentInfo
    id: str
    VERSION: ClassVar[int]

    @classmethod
    def from_dict(
        cls, d: Dict[str, Any], api_key: Optional[str] = None
    ) -> "BaseMessage":
        version = d.pop("VERSION")
        if version == 1:
            # Message was dumped before id was introduced. We can generate it now.
            d["id"] = generate_id()

        experiment_info_dict: Dict[str, Optional[str]] = d.pop("experiment_info_")
        experiment_info_ = experiment_info.get(
            **experiment_info_dict,
            api_key=api_key,
            api_key_not_found_message=logging_messages.API_KEY_NOT_CONFIGURED
        )

        return cls(experiment_info_=experiment_info_, **d)

    def to_dict(self) -> Dict[str, Any]:
        result = dataclasses.asdict(self)

        del result["experiment_info_"]["api_key"]
        result["VERSION"] = self.VERSION

        return result


@dataclasses.dataclass
class PromptMessage(BaseMessage):
    prompt_asset_data: Dict[str, Any]
    duration: Optional[float]
    metadata: Optional[Dict[str, Union[str, bool, float, None]]]
    tags: Optional[List[str]]

    VERSION: ClassVar[int] = 2


@dataclasses.dataclass
class ChainMessage(BaseMessage):
    chain_data: Dict[str, JSONEncodable]
    duration: float
    tags: Optional[List[str]]
    metadata: Optional[Dict[str, JSONEncodable]]
    others: Dict[str, JSONEncodable]
    # 'other' - is a name of an attribute of experiment, logged via log_other

    VERSION: ClassVar[int] = 2
