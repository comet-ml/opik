import queue
import threading
from typing import Any, List, Optional

from . import messages, queue_consumer
from .. import synchronization
from .batching import batch_manager


class Streamer:
    def __init__(
        self,
        message_queue: "queue.Queue[Any]",
        queue_consumers: List[queue_consumer.QueueConsumer],
        batch_manager: Optional[batch_manager.BatchManager],
    ) -> None:
        self._lock = threading.RLock()
        self._message_queue = message_queue
        self._queue_consumers = queue_consumers
        self._batch_manager = batch_manager

        self._drain = False

        self._start_queue_consumers()

        if self._batch_manager is not None:
            self._batch_manager.start()

    def put(self, message: messages.BaseMessage) -> None:
        with self._lock:
            if self._drain:
                return

            if (
                self._batch_manager is not None
                and self._batch_manager.message_supports_batching(message)
            ):
                self._batch_manager.process_message(message)
            else:
                self._message_queue.put(message)

    def close(self, timeout: Optional[int]) -> bool:
        """
        Stops data sending threads
        """
        with self._lock:
            self._drain = True

        if self._batch_manager is not None:
            self._batch_manager.stop()  # stopping causes adding remaining batch messages to the queue

        self.flush(timeout)
        self._close_queue_consumers()

        return self._message_queue.empty()

    def flush(self, timeout: Optional[int]) -> None:
        if self._batch_manager is not None:
            self._batch_manager.flush()

        synchronization.wait_for_done(
            check_function=lambda: (
                self.workers_waiting()
                and self._message_queue.empty()
                and (self._batch_manager is None or self._batch_manager.is_empty())
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
