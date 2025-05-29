import threading
import logging
import time
from typing import List, Optional

from . import messages, message_queue, queue_consumer
from .. import synchronization
from .batching import batch_manager
from ..file_upload import base_upload_manager
from .. import _logging

LOGGER = logging.getLogger(__name__)


class Streamer:
    def __init__(
        self,
        queue: message_queue.MessageQueue[messages.BaseMessage],
        queue_consumers: List[queue_consumer.QueueConsumer],
        batch_manager: Optional[batch_manager.BatchManager],
        file_upload_manager: base_upload_manager.BaseFileUploadManager,
    ) -> None:
        self._lock = threading.RLock()
        self._message_queue = queue
        self._queue_consumers = queue_consumers
        self._batch_manager = batch_manager
        self._file_upload_manager = file_upload_manager

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
            elif base_upload_manager.message_supports_upload(message):
                self._file_upload_manager.upload(message)
            else:
                if self._message_queue.accept_put_without_discarding() is False:
                    _logging.log_once_at_level(
                        logging.WARNING,
                        "The message queue size limit has been reached. The new message has been added to the queue, and the oldest message has been discarded.",
                        logger=LOGGER,
                    )
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

    def flush(self, timeout: Optional[float], upload_sleep_time: int = 5) -> bool:
        if self._batch_manager is not None:
            self._batch_manager.flush()

        start_time = time.time()

        synchronization.wait_for_done(
            check_function=lambda: self._all_done(),
            timeout=timeout,
            sleep_time=0.1,
        )

        elapsed_time = time.time() - start_time
        if timeout is not None:
            timeout = timeout - elapsed_time
            if timeout < 0.0:
                timeout = 1.0

        # flushing upload manager is blocking operation
        upload_flushed = self._file_upload_manager.flush(
            timeout=timeout, sleep_time=upload_sleep_time
        )

        return upload_flushed and self._all_done()

    def _all_done(self) -> bool:
        return (
            self.workers_idling()
            and self._message_queue.empty()
            and (self._batch_manager is None or self._batch_manager.is_empty())
        )

    def workers_idling(self) -> bool:
        return all([consumer.idling for consumer in self._queue_consumers])

    def queue_size(self) -> int:
        return self._message_queue.size()

    def _start_queue_consumers(self) -> None:
        for consumer in self._queue_consumers:
            consumer.start()

    def _close_queue_consumers(self) -> None:
        for consumer in self._queue_consumers:
            consumer.close()
