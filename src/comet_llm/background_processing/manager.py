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
from typing import Any, Optional
from threading import Thread, Lock

from ..background_processing import sender
from ..background_processing import consumer

CLOSE_MESSAGE = object()


class QueueManager:
    def __init__(
        self,
        message_sender: sender.MessageSender,
    ) -> None:
        self.lock = Lock()
        self.sender_queue: Optional["queue.Queue[Any]"] = None
        self.background_sender: Optional[consumer.QueueConsumer] = None
        self.background_thread: Optional[Thread] = None
        self.drain = False

        self._message_sender = message_sender

    def _prepare(self) -> "queue.Queue[Any]":
        if self.sender_queue is None:
            sender_queue: "queue.Queue[Any]" = queue.Queue()
            self.sender_queue = sender_queue
            self.queue_consumer = consumer.QueueConsumer(
                message_queue=self.sender_queue,
                message_sender=self._message_sender,
            )

        if self.background_thread is None:
            assert self.queue_consumer is not None
            self.background_thread = Thread(
                target=self.queue_consumer.run, daemon=True, name="QueueManager"
            )
            self.background_thread.start()

        assert self.sender_queue is not None
        return self.sender_queue

    def put(self, item: Any) -> None:
        with self.lock:
            if not self.drain:

                if self.sender_queue is None:
                    sender_queue = self._prepare()
                else:
                    sender_queue = self.sender_queue

                sender_queue.put(item)

    def close(self) -> None:
        with self.lock:
            self.drain = True

        if self.sender_queue is not None:
            self.sender_queue.put(CLOSE_MESSAGE)

        if self.background_thread is not None:
            self.background_thread.join(10)

        if self.queue_consumer is not None:
            self.queue_consumer.close()