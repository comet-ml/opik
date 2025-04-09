import time
import queue
import threading
from typing import Any, Optional

from . import message_processors

SLEEP_BETWEEN_LOOP_ITERATIONS = 0.1


class QueueConsumer(threading.Thread):
    def __init__(
        self,
        message_queue: "queue.Queue[Any]",
        message_processor: message_processors.BaseMessageProcessor,
        name: Optional[str] = None,
    ):
        super().__init__(daemon=True, name=name)
        self._message_queue = message_queue
        self._message_processor = message_processor
        self._processing_stopped = False
        self.waiting = True

    def run(self) -> None:
        while self._processing_stopped is False:
            self._loop()

        return

    def _loop(self) -> None:
        try:
            self.waiting = True
            message = self._message_queue.get(timeout=SLEEP_BETWEEN_LOOP_ITERATIONS)
            self.waiting = False

            if message is None:
                return

            self._message_processor.process(message)

        except queue.Empty:
            time.sleep(SLEEP_BETWEEN_LOOP_ITERATIONS)
        except Exception:
            # TODO
            pass

    def close(self) -> None:
        self._processing_stopped = True
