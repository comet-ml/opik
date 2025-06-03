import logging
import threading
import time
from queue import Empty
from typing import Optional

from . import message_processors, message_queue, messages
from .. import exceptions, _logging

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
        self.idling = True
        self.next_message_time = time.monotonic()

    def run(self) -> None:
        while self._processing_stopped is False:
            self._loop()

        return

    def _loop(self) -> None:
        now = time.monotonic()
        if now < self.next_message_time:
            # mark as not idling because we still have work to do
            self.idling = False
            time.sleep(SLEEP_BETWEEN_LOOP_ITERATIONS)
            return

        message = None
        try:
            self.idling = True
            message = self._message_queue.get(timeout=SLEEP_BETWEEN_LOOP_ITERATIONS)
            self.idling = False

            if message is None:
                return
            elif message.delivery_time <= now:
                self._message_processor.process(message)
            else:
                # put a message back to keep an order in the queue
                self._push_message_back(message)

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
            # set the next iteration time to avoid rate limiting
            self.next_message_time = now + limit_exception.retry_after
            if message is not None:
                message.delivery_time = self.next_message_time
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
        if self._message_queue.accept_put_without_discarding() is False:
            _logging.log_once_at_level(
                logging.WARNING,
                "The message queue size limit has been reached. The current message has been returned to the queue, and the newest message has been discarded.",
                logger=LOGGER,
            )
        self._message_queue.put_back(message)
