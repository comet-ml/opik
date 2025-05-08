import time
import threading
from typing import Optional
from queue import Empty

from . import message_processors, message_queue, messages
from .. import exceptions

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
        self.waiting = True
        self.next_message_time = time.monotonic()

    def run(self) -> None:
        while self._processing_stopped is False:
            self._loop()

        return

    def _loop(self) -> None:
        now = time.monotonic()
        if now < self.next_message_time:
            self.waiting = True
            time.sleep(SLEEP_BETWEEN_LOOP_ITERATIONS)
            return

        message = None
        try:
            self.waiting = True
            message = self._message_queue.get(timeout=SLEEP_BETWEEN_LOOP_ITERATIONS)
            self.waiting = False

            if message is None:
                return
            elif message.delivery_time <= now:
                self._message_processor.process(message)
            else:
                # put back to keep an order in the queue
                self._message_queue.put_back(message)

        except Empty:
            time.sleep(SLEEP_BETWEEN_LOOP_ITERATIONS)
        except exceptions.OpikCloudRequestsRateLimited as limit_exception:
            # set the next iteration time to avoid rate limiting
            self.next_message_time = now + limit_exception.retry_after
            if message is not None:
                message.delivery_time = self.next_message_time
                # put back to keep an order in the queue
                self._message_queue.put_back(message)
        except Exception:
            # TODO
            pass

    def close(self) -> None:
        self._processing_stopped = True
