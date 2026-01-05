import threading
import logging
import time
from typing import List, Optional

from . import messages, message_queue, queue_consumer
from .. import _logging
from .. import synchronization
from .preprocessing import (
    attachments_preprocessor,
    batching_preprocessor,
    file_upload_preprocessor,
)


LOGGER = logging.getLogger(__name__)


class Streamer:
    def __init__(
        self,
        queue: message_queue.MessageQueue[messages.BaseMessage],
        queue_consumers: List[queue_consumer.QueueConsumer],
        attachments_preprocessor: attachments_preprocessor.AttachmentsPreprocessor,
        batch_preprocessor: batching_preprocessor.BatchingPreprocessor,
        upload_preprocessor: file_upload_preprocessor.FileUploadPreprocessor,
    ) -> None:
        self._lock = threading.RLock()
        self._message_queue = queue
        self._queue_consumers = queue_consumers
        self._attachments_preprocessor = attachments_preprocessor
        self._batch_preprocessor = batch_preprocessor
        self._upload_preprocessor = upload_preprocessor

        self._drain = False

        self._idle = True

        self._start_queue_consumers()
        self._batch_preprocessor.start()

    def put(self, message: messages.BaseMessage) -> None:
        with self._lock:
            if self._drain:
                return

            self._idle = False
            try:
                # do embedded attachments pre-processing first (MUST ALWAYS BE DONE FIRST)
                preprocessed_message = self._attachments_preprocessor.preprocess(
                    message
                )

                # do file uploads pre-processing second
                preprocessed_message = self._upload_preprocessor.preprocess(
                    preprocessed_message
                )

                # do batching pre-processing third
                preprocessed_message = self._batch_preprocessor.preprocess(
                    preprocessed_message
                )

                # work with resulting message if not fully consumed by preprocessors
                if preprocessed_message is not None:
                    if self._message_queue.accept_put_without_discarding() is False:
                        _logging.log_once_at_level(
                            logging.WARNING,
                            "The message queue size limit has been reached. The new message has been added to the queue, and the oldest message has been discarded.",
                            logger=LOGGER,
                        )
                    self._message_queue.put(preprocessed_message)
            except Exception as ex:
                LOGGER.error(
                    "Failed to process message by streamer: %s", ex, exc_info=ex
                )
            self._idle = True

    def close(self, timeout: Optional[int]) -> bool:
        """
        Stops data processing threads
        """
        with self._lock:
            synchronization.wait_for_done(
                check_function=lambda: self._idle,
                timeout=timeout,
                sleep_time=0.1,
            )
            self._drain = True

        self._batch_preprocessor.stop()  # stopping causes adding remaining batch messages to the queue

        self.flush(timeout)
        self._close_queue_consumers()

        return self._message_queue.empty()

    def flush(self, timeout: Optional[float], upload_sleep_time: int = 5) -> bool:
        # wait for current pending messages processing to be completed
        # this should be done before flushing batch preprocessor because some
        # batch messages may be added to the queue during processing
        with self._lock:
            synchronization.wait_for_done(
                check_function=lambda: self._idle,
                timeout=timeout,
                sleep_time=0.1,
            )

        self._batch_preprocessor.flush()

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
        upload_flushed = self._upload_preprocessor.flush(
            timeout=timeout, sleep_time=upload_sleep_time
        )

        flushed = upload_flushed and self._all_done()
        LOGGER.debug(f"Streamer flushed completely: {flushed}")

        return flushed

    def _all_done(self) -> bool:
        return (
            self.workers_idling()
            and self._message_queue.empty()
            and self._batch_preprocessor.is_empty()
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
