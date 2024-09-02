import queue
import threading
from typing import Any, List, Optional

from . import messages, queue_consumer
from .. import synchronization


class Streamer:
    def __init__(
        self,
        message_queue: "queue.Queue[Any]",
        queue_consumers: List[queue_consumer.QueueConsumer],
    ) -> None:
        self._lock = threading.Lock()
        self._message_queue = message_queue
        self._queue_consumers = queue_consumers
        self._drain = False

        self._start_queue_consumers()

    def put(self, message: messages.BaseMessage) -> None:
        with self._lock:
            if not self._drain:
                self._message_queue.put(message)

    def close(self, timeout: Optional[int]) -> bool:
        """
        Stops data sending threads
        """
        with self._lock:
            self._drain = True

        self.flush(timeout)
        self._close_queue_consumers()

        return self._message_queue.empty()

    def flush(self, timeout: Optional[int]) -> None:
        synchronization.wait_for_done(
            check_function=lambda: (
                self.workers_waiting() and self._message_queue.empty()
            ),
            timeout=timeout,
            sleep_time=0.1,
        )

    def workers_waiting(self) -> bool:
        return all([consumer.waiting for consumer in self._queue_consumers])

    def _start_queue_consumers(self) -> None:
        for consumer in self._queue_consumers:
            consumer.start()

    def _close_queue_consumers(self) -> None:
        for consumer in self._queue_consumers:
            consumer.close()
