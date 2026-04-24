import threading
import logging
import time
from typing import List, Optional

from opik.file_upload import base_upload_manager
from . import messages, message_queue, queue_consumer
from .. import _logging
from .. import synchronization
from .preprocessing import (
    attachments_preprocessor,
    batching_preprocessor,
)
from .replay import replay_manager


LOGGER = logging.getLogger(__name__)


class Streamer:
    def __init__(
        self,
        queue: message_queue.MessageQueue[messages.BaseMessage],
        queue_consumers: List[queue_consumer.QueueConsumer],
        attachments_preprocessor: attachments_preprocessor.AttachmentsPreprocessor,
        batch_preprocessor: batching_preprocessor.BatchingPreprocessor,
        file_uploader: base_upload_manager.BaseFileUploadManager,
        fallback_replay_manager: replay_manager.ReplayManager,
    ) -> None:
        self._lock = threading.RLock()
        self._message_queue = queue
        self._queue_consumers = queue_consumers
        self._attachments_preprocessor = attachments_preprocessor
        self._batch_preprocessor = batch_preprocessor
        self._file_upload_manager = file_uploader
        self._fallback_replay_manager = fallback_replay_manager

        self._drain = False

        self._idle = True

        self._start_queue_consumers()
        self._batch_preprocessor.start()

        self._fallback_replay_manager.set_replay_callback(self.put)
        self._fallback_replay_manager.start()

    @property
    def use_batching(self) -> bool:
        return self._batch_preprocessor._batch_manager is not None

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

    def close(self, timeout: Optional[int] = None, *, flush: bool = True) -> bool:
        """
        Stops data processing threads.

        Args:
            timeout: Budget for draining the pipeline. Only meaningful when
                ``flush`` is True; ignored otherwise.
            flush: If True (default), wait for queued messages and file uploads
                to reach the backend before closing — the historical
                production-safe behaviour. Set False for fire-and-forget
                teardowns where pending data can be dropped (e.g. per-test
                cleanup in e2e tests where assertions already polled the
                backend during the test body).
        """
        with self._lock:
            if self._drain:
                # Already closed — make the call idempotent so atexit can fire
                # safely after an explicit close (common in test teardown).
                return self._message_queue.empty()
            if flush:
                synchronization.wait_for_done(
                    check_function=lambda: self._idle,
                    timeout=timeout,
                    sleep_time=0.1,
                )
            self._drain = True

        self._batch_preprocessor.stop(flush=flush)
        self._fallback_replay_manager.close()

        if flush:
            # Wait for the replay thread, consumer queue, and file uploads to
            # actually drain before releasing the caller. Consumers must keep
            # running while the queue drains, so close them at the very end.
            self._fallback_replay_manager.join(timeout)
            self.flush(timeout)
            self._close_queue_consumers()
        else:
            # Fire-and-forget: drop pending messages so the stop-signalled
            # consumers see an empty queue and exit on their own. No joins —
            # daemon threads can finish any in-flight HTTP request in the
            # background without blocking teardown.
            pending = self._message_queue.size()
            if pending > 0:
                LOGGER.warning(
                    "Streamer.close(flush=False) discarding %d queued message(s) "
                    "without flushing. Data that had not yet reached the backend "
                    "will be lost. Use flush=True (the default) if you need "
                    "durability — flush=False is intended for short-lived "
                    "tests/teardowns, not production shutdown.",
                    pending,
                )
            self._message_queue.clear()
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

        if self._fallback_replay_manager.has_server_connection:
            # do replay only if we have a connection to the server
            self._fallback_replay_manager.flush()

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

        flushed = upload_flushed and self._all_done()
        LOGGER.debug(f"Streamer flushed completely: {flushed}")

        return flushed

    def _all_done(self) -> bool:
        return (
            self.workers_idling()
            and self._message_queue.empty()
            and self._batch_preprocessor.is_empty()
        )

    def __internal_api__failed_uploads__(self, timeout: Optional[float]) -> int:
        """Returns the number of failed file uploads. Blocking - waits for all uploads to complete."""
        return self._file_upload_manager.failed_uploads(timeout=timeout)

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
