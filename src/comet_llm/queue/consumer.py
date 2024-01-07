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

from comet_llm.messages import sender

import queue
from typing import Any


class QueueConsumer:
    def __init__(
        self,
        message_queue: "queue.Queue[Any]",
        message_sender: sender.MessageSender,
    ):
        self.message_queue = message_queue
        self._message_sender = message_sender
        self.stop_processing = False


    def run(self) -> None:
        while self.stop_processing is False:
            stop = self._loop()

            if stop is True:
                break

        return

    def _loop(self) -> bool:
        try:
            message = self.message_queue.get(block=True)
            if message is None:
                return False

            if message is CLOSE_MESSAGE:
                self.stop_processing = True
                return True
            
            self._message_sender.send(message)

        except queue.Empty:
            pass

        return False