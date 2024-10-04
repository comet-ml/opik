import queue
import threading
import logging
from typing import Any, List, Optional

from . import messages, queue_consumer
from .. import synchronization
from .batching import batch_manager

from .. import url_helpers

LOGGER = logging.getLogger(__name__)


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

        # Used to know when to display the project URL
        self._project_name_most_recent_trace: Optional[str] = None

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

        # Display message in console
        if isinstance(message, messages.CreateTraceMessage):
            projects_url = url_helpers.get_projects_url()
            project_name = message.project_name
            if (
                self._project_name_most_recent_trace is None
                or self._project_name_most_recent_trace != project_name
            ):
                LOGGER.info(
                    f'Started logging traces to the "{project_name}" project at {projects_url}.'
                )
                self._project_name_most_recent_trace = project_name

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
