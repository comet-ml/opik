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

import queue
import time
from typing import Any

from . import online_message_dispatcher, sentinel


class QueueConsumer:
    def __init__(
        self,
        message_queue: "queue.Queue[Any]",
        message_dispatcher: online_message_dispatcher.MessageDispatcher,
    ):
        self._message_queue = message_queue
        self._message_dispatcher = message_dispatcher
        self._stop_processing = False

    def run(self) -> None:
        while self._stop_processing is False:
            print("consumer iteration")
            stop = self._loop()

            if stop is True:
                break

        return

    def _loop(self) -> bool:
        try:
            message = self._message_queue.get(block=True)
            if message is None:
                return False

            if message is sentinel.END_SENTINEL:
                self._stop_processing = True
                return True

            self._message_dispatcher.send(message)

        except queue.Empty:
            pass

        return False

    def close(self) -> None:
        """For the BackgroundSender to stop processing messages"""
        self._stop_processing = True
