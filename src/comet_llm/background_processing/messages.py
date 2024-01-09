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

import abc
from typing import Dict, Any, Optional, Union, List
from .. import experiment_info


class BaseMessage(abc.ABC):
    pass


class PromptMessage(BaseMessage):
    def __init__(
        self,
        experiment_information: experiment_info.ExperimentInfo,
        prompt_asset_data: Dict[str, Any],
        duration: Optional[float],
        metadata: Optional[Dict[str, Union[str, bool, float, None]]],
        tags: Optional[List[str]],
    ) -> None:
        super().__init__()

        self.experiment_information = experiment_information
        self.prompt_asset_data = prompt_asset_data
        self.duration = duration
        self.metadata = metadata
        self.tags = tags


class UserFeedbackMessage(BaseMessage):
    pass


class ChainMessage(BaseMessage):
    pass
