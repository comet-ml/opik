import threading
import time

from typing import List, Callable, Optional
from .. import messages


class SpansBatcher:
    def __init__(
        self,
        max_batch_size: int = 1000,
        flush_interval: float = 1.0,
    ):
        self._accumulated_messages: List[messages.CreateSpanMessage] = []
        self._flush_interval = flush_interval
        self._lock = threading.Lock()
        self._stop_event = threading.Event()

        self._max_batch_size = max_batch_size

        self._flush_callback: Optional[Callable[[messages.BaseMessage], None]] = None
        self._flushing_thread: Optional[threading.Thread] = None
        self._started = False

    def add(self, message: messages.CreateSpanMessage) -> None:
        assert self._started

        self._accumulated_messages.append(message)
        if self._accumulated_messages == self._max_batch_size:
            self.flush()

    def _flushing_job(self) -> None:
        while not self._stop_event.is_set():
            time.sleep(self._flush_interval)
            self.flush()

    def stop(self) -> None:
        assert self._started

        self._stop_event.set()
        self._flushing_thread.join()
        self.flush()

    def start(self, flush_callback: Callable[[messages.BaseMessage], None]):
        self._flushing_thread = threading.Thread(target=self._flushing_job)
        self._flushing_thread.daemon = True
        self._flush_callback = flush_callback

        self._started = True
        self._flushing_thread.start()

    def flush(self) -> None:
        assert self._started

        with self._lock:
            if len(self._accumulated_messages) > 0:
                batch_message = self._create_batch_from_accumulated_messages()
                self._accumulated_messages = []

                self._flush_callback(batch_message)

    def _create_batch_from_accumulated_messages(
        self,
    ) -> messages.CreateSpansBatchMessage:
        return messages.CreateSpansBatchMessage(batch=self._accumulated_messages)
