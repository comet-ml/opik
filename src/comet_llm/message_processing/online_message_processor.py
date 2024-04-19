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

import logging
from typing import Optional

from .. import llm_result
from . import messages
from .online_senders import chain, prompt

LOGGER = logging.getLogger(__name__)


class OnlineMessageProcessor:
    def __init__(self) -> None:
        pass

    def process(self, message: messages.BaseMessage) -> Optional[llm_result.LLMResult]:
        if isinstance(message, messages.PromptMessage):
            return prompt.send(message)
        elif isinstance(message, messages.ChainMessage):
            return chain.send(message)

        LOGGER.debug(f"Unsupported message type {message}")
        return None
