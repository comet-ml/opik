import threading
import time
import abc

from typing import List, Callable
from .. import messages


BASE_BATCH_MEMORY_LIMIT_MB = 50


class BaseBatcher(abc.ABC):
    def __init__(
        self,
        flush_callback: Callable[[messages.BaseMessage], None],
        max_batch_size: int,
        flush_interval_seconds: float,
        batch_memory_limit_mb: int = BASE_BATCH_MEMORY_LIMIT_MB,
    ):
        self._flush_interval_seconds: float = flush_interval_seconds
        self._flush_callback: Callable[[messages.BaseMessage], None] = flush_callback
        self._accumulated_messages: List[messages.BaseMessage] = []
        self._max_batch_size: int = max_batch_size
        self._batch_memory_limit_mb: int = batch_memory_limit_mb

        self._last_time_flush_callback_called: float = time.time()
        self._lock = threading.RLock()

    def flush(self) -> None:
        with self._lock:
            if len(self._accumulated_messages) > 0:
                batch_messages = self._create_batches_from_accumulated_messages()
                self._accumulated_messages = []

                for batch_message in batch_messages:
                    self._flush_callback(batch_message)
            self._last_time_flush_callback_called = time.time()

    def is_ready_to_flush(self) -> bool:
        elapsed = time.time() - self._last_time_flush_callback_called
        return elapsed >= self._flush_interval_seconds

    def is_empty(self) -> bool:
        with self._lock:
            return len(self._accumulated_messages) == 0

    @abc.abstractmethod
    def _create_batches_from_accumulated_messages(
        self,
    ) -> List[messages.BaseMessage]: ...

    @abc.abstractmethod
    def add(self, message: messages.BaseMessage) -> None:
        with self._lock:
            self._accumulated_messages.append(message)
            if len(self._accumulated_messages) >= self._max_batch_size:
                self.flush()

    def _remove_matching_messages(
        self, filter_func: Callable[[messages.BaseMessage], bool]
    ) -> None:
        """
        Remove messages from _accumulated_messages that match the provided filter function.

        Args:
            filter_func: A function that takes a BaseMessage and returns True if the message should be removed
        """
        with self._lock:
            self._accumulated_messages = list(
                filter(lambda x: not filter_func(x), self._accumulated_messages)
            )

    def size(self) -> int:
        """
        Gets the total number of accumulated messages in the current instance.

        The method calculates and retrieves the count of accumulated messages
        maintained within an internal structure. Thread safety is guaranteed
        through the usage of a lock mechanism.

        Returns:
            int: The total number of accumulated messages.
        """
        with self._lock:
            return len(self._accumulated_messages)
