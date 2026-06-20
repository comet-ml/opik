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

    def put(self, message: messages.BaseMessage, force: bool = False) -> bool:
        """Enqueue a message for background processing.

        Returns ``True`` if the message was accepted by the streamer (queued or
        consumed by a preprocessor). Returns ``False`` if the streamer is draining
        or if preprocessing failed, so callers that need durability can react.
        """
        with self._lock:
            if self._drain and not force:
                return False

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

                return True
            except Exception as ex:
                LOGGER.error(
                    "Failed to process message by streamer: %s", ex, exc_info=ex
                )
                return False
            finally:
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
                # Replay any FAILED messages while the streamer still accepts
                # new messages. After _drain=True the replay callback's put()
                # would be dropped, so this must happen first.
                self._fallback_replay_manager.flush()
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

    def drain_to_processors(self, timeout: Optional[float] = None) -> bool:
        """Lightweight drain: ensure every message submitted so far has
        been applied to in-process chained processors (notably the
        `LocalEmulatorMessageProcessor`).

        Differs from `flush(...)` by skipping the file-upload manager
        and the fallback replay manager — both are concerned with
        backend delivery, not local processor state. Designed to be
        called frequently from the evaluation engine before invoking
        the agentic LLM judge, which reads the emulator's view of
        spans/error_info for the most-recently-run task. Without this
        drain, the queue consumer may still be processing the batch
        when scoring begins and the judge would see stale data.

        Returns True if everything drained within `timeout`; False
        if the timeout fired with messages still pending. The agentic
        path treats False as "best-effort applied" and proceeds with
        whatever state is currently in the emulator.
        """
        self._batch_preprocessor.flush()
        synchronization.wait_for_done(
            check_function=lambda: self._all_done(),
            timeout=timeout,
            sleep_time=0.05,
            progress_callback=self._batch_preprocessor.flush,
        )
        return self._all_done()

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
            progress_callback=self._batch_preprocessor.flush,
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
        # `all_tasks_done()` is True only when every message accepted by
        # `put()` has been terminally handled by a consumer (its
        # `message_processor.process(...)` returned or raised a non-rate-limit
        # error). This closes the race where a message was popped off the
        # queue but not yet processed.
        return (
            self._message_queue.all_tasks_done() and self._batch_preprocessor.is_empty()
        )

    def __internal_api__failed_uploads__(self, timeout: Optional[float]) -> int:
        """Returns the number of failed file uploads. Blocking - waits for all uploads to complete."""
        return self._file_upload_manager.failed_uploads(timeout=timeout)

    def queue_size(self) -> int:
        return self._message_queue.size()

    def _start_queue_consumers(self) -> None:
        for consumer in self._queue_consumers:
            consumer.start()

    def _close_queue_consumers(self) -> None:
        for consumer in self._queue_consumers:
            consumer.close()
