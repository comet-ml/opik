# -*- coding: utf-8 -*-
# *******************************************************
#   ____                     _               _
#  / ___|___  _ __ ___   ___| |_   _ __ ___ | |
# | |   / _ \| '_ ` _ \ / _ \ __| | '_ ` _ \| |
# | |__| (_) | | | | | |  __/ |_ _| | | | | | |
#  \____\___/|_| |_| |_|\___|\__(_)_| |_| |_|_|
#
#  Sign up for free at https://www.comet.com
#  Copyright (C) 2015-2024 Comet ML INC
#  This source code is licensed under the MIT license found in the
#  LICENSE file in the root directory of this package.
# *******************************************************

import logging
import os
import pathlib
import threading
import time
from typing import Optional

from .. import llm_result
from . import messages
from .offline_senders import chain, prompt

LOGGER = logging.getLogger(__name__)


class OfflineMessageProcessor:
    def __init__(self, offline_directory: str, batch_duration_seconds: float) -> None:
        self._offline_directory = offline_directory
        self._batch_duration_seconds = batch_duration_seconds
        self._lock = threading.Lock()

        self._last_file_started_at: Optional[float] = None
        self._last_file: Optional[str] = None

    def process(self, message: messages.BaseMessage) -> None:
        with self._lock:
            file_name = pathlib.Path(self._offline_directory, "some_file.jsonl")

            if isinstance(message, messages.PromptMessage):
                try:
                    return prompt.send_prompt(message, file_name)
                except Exception:
                    LOGGER.error("Failed to log prompt", exc_info=True)
            elif isinstance(message, messages.ChainMessage):
                try:
                    return chain.send_chain(message, file_name)
                except Exception:
                    LOGGER.error("Failed to log chain", exc_info=True)

        LOGGER.debug(f"Unsupported message type {message}")
        return None

    # def _get_file_name(self):
    #     if self._last_file_started_at is None:
    #         self._last_file_started_at = time.time()
    #     elif (self._last_file_started_at - time.time()) > self._batch_duration_seconds:
    #         self._last_file_started_at = time.time()

    #     result = f"{}_{time.time()}.jsonl"
