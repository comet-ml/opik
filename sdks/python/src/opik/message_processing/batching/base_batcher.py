import threading
import time
import abc

from typing import List, Callable
from .. import messages


class BaseBatcher(abc.ABC):
    def __init__(
        self,
        flush_callback: Callable[[messages.BaseMessage], None],
        max_batch_size: int,
        flush_interval: float,
    ):
        self._flush_interval = flush_interval
        self._flush_callback = flush_callback
        self._accumulated_messages: List[messages.BaseMessage] = []
        self._max_batch_size = max_batch_size

        self._lock = threading.RLock()
        self._last_time_flushed = 0

    def add(self, message: messages.BaseMessage) -> None:
        with self._lock:
            self._accumulated_messages.append(message)
            if self._accumulated_messages == self._max_batch_size:
                self.flush()

    def flush(self) -> None:
        with self._lock:
            if len(self._accumulated_messages) > 0:
                batch_message = self._create_batch_from_accumulated_messages()
                self._accumulated_messages = []

                self._flush_callback(batch_message)
                self._last_time_flushed = time.time()

    def is_ready_to_flush(self) -> bool:
        return (time.time() - self._last_time_flushed) >= self._flush_interval

    def is_empty(self) -> bool:
        with self._lock:
            return len(self._accumulated_messages) == 0

    @abc.abstractmethod
    def _create_batch_from_accumulated_messages(self) -> messages.BaseMessage: ...
