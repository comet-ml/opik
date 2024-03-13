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
from . import queue_consumer
from . import messages

class Streamer:
    def __init__(
        self,
        message_queue: queue.Queue,
        queue_consumer: queue_consumer.QueueConsumer
    ) -> None:
        self._lock = Lock()
        self._message_queue = message_queue
        self._queue_consumer = queue_consumer
        self._drain = False

        self._background_thread = Thread(
            target=self._queue_consumer.run,
            daemon=True,
            name="QueueConsumerThread"
        )
        self._background_thread.start()

    def put(self, item: Any) -> None:
        with self._lock:
            if not self._drain:
                self._message_queue.put(item)

    def close(self, timeout) -> None:
        with self._lock:
            self._drain = True

        self._message_queue.put(messages.SENTINEL_CLOSE_MESSAGE)
        self._background_thread.join(timeout)
        self._queue_consumer.close()


def get() -> Streamer:
    message_queue = queue.Queue()
    queue_consumer_ = queue_consumer.QueueConsumer(
        message_queue=message_queue,
        message_sender=sender.MessageSender()
    )

    streamer = Streamer(
        message_queue=message_queue,
        queue_consumer=queue_consumer_
    )

    return streamer


