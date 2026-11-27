import logging
import threading
import time
from queue import Empty
from typing import Optional

from . import message_queue, messages
from .processors import message_processors
from .. import exceptions

LOGGER = logging.getLogger(__name__)


SLEEP_BETWEEN_LOOP_ITERATIONS = 0.1


class QueueConsumer(threading.Thread):
    def __init__(
        self,
        queue: message_queue.MessageQueue[messages.BaseMessage],
        message_processor: message_processors.BaseMessageProcessor,
        name: Optional[str] = None,
    ):
        super().__init__(daemon=True, name=name)
        self._message_queue = queue
        self._message_processor = message_processor
        self._processing_stopped = False
        self.next_message_time = time.monotonic()

    def run(self) -> None:
        while self._processing_stopped is False:
            self._loop()

        return

    def _loop(self) -> None:
        now = time.monotonic()
        # The queue-wide pause is shared by every consumer; a 429 seen by one
        # of them must stop all deliveries until the Retry-After deadline.
        paused_until = self._message_queue.get_delivery_paused_until()
        if now < self.next_message_time or now < paused_until:
            time.sleep(SLEEP_BETWEEN_LOOP_ITERATIONS)
            return

        message = None
        try:
            message = self._message_queue.get(timeout=SLEEP_BETWEEN_LOOP_ITERATIONS)

            if message is None:
                return

            if (
                message.delivery_time <= now
                and time.monotonic() >= self._message_queue.get_delivery_paused_until()
            ):
                self._process_message(message)
            else:
                # put a message back to keep an order in the queue
                self._push_message_back(message)
                # The deferred message stays at the head; without a sleep here
                # the other consumers repeatedly pop and re-enqueue it.
                time.sleep(SLEEP_BETWEEN_LOOP_ITERATIONS)

        except Empty:
            time.sleep(SLEEP_BETWEEN_LOOP_ITERATIONS)
        except exceptions.OpikCloudRequestsRateLimited as limit_exception:
            LOGGER.info(
                "Ingestion rate limited, retrying in %s seconds, remaining queue size: %d, details: %s",
                limit_exception.retry_after,
                len(self._message_queue)
                + 1,  # add 1 to account for the current message
                limit_exception.headers,
            )
            # Set both this consumer's local deadline (kept for the existing
            # single-consumer contract) and the queue-wide one the other
            # consumers check before taking any message.
            retry_until = time.monotonic() + limit_exception.retry_after
            self.next_message_time = retry_until
            self._message_queue.set_delivery_paused_until(retry_until)
            if message is not None:
                message.delivery_time = retry_until
                # put a message back to keep an order in the queue
                self._push_message_back(message)
        except Exception as ex:
            LOGGER.error(
                "Failed to process message, unexpected error: %s", ex, exc_info=ex
            )
            pass

    def close(self) -> None:
        self._processing_stopped = True

    def _push_message_back(self, message: messages.BaseMessage) -> None:
        message.delivery_attempts += 1
        self._message_queue.put_back(message)

    def _process_message(self, message: messages.BaseMessage) -> None:
        try:
            self._message_processor.process(message)
        except exceptions.OpikCloudRequestsRateLimited:
            # Caller will re-enqueue the message via put_back; the task is
            # still in flight, so leave the queue's unfinished-task counter
            # untouched and let the rate-limit handler in `_loop` run.
            raise
        except Exception:
            # Permanent failure: the message was popped and will not be
            # retried. Count it as done so quiescence bookkeeping doesn't
            # stall (`join` / `all_tasks_done`).
            self._message_queue.task_done()
            raise
        else:
            self._message_queue.task_done()
