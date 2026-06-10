import logging
import threading
from typing import Type, Dict
from .. import messages
from . import base_batcher
from . import flushing_thread

LOGGER = logging.getLogger(__name__)


class BatchManager:
    def __init__(
        self,
        message_to_batcher_mapping: Dict[
            Type[messages.BaseMessage], base_batcher.BaseBatcher
        ],
    ) -> None:
        self._message_to_batcher_mapping = message_to_batcher_mapping
        self._lock = threading.RLock()
        self._flushing_thread = flushing_thread.FlushingThread(
            flush_callable=self.flush_ready,
        )

    def start(self) -> None:
        self._flushing_thread.start()

    def stop(self, flush: bool = True) -> None:
        """Stop the background flushing thread.

        Args:
            flush: If True (default), also flush any pending batches to the
                downstream queue before the thread exits — the historical
                behaviour. Set False to skip the final flush; pending batches
                are dropped. Only useful for fire-and-forget teardowns in
                tests that no longer care about pending data.
        """
        with self._lock:
            self._flushing_thread.close()
            if flush:
                self.flush()

    def message_supports_batching(self, message: messages.BaseMessage) -> bool:
        if message is None:
            return False

        if hasattr(message, "supports_batching"):
            return message.supports_batching

        return message.__class__ in self._message_to_batcher_mapping

    def process_message(self, message: messages.BaseMessage) -> None:
        with self._lock:
            self._message_to_batcher_mapping[type(message)].add(message)

    def is_empty(self) -> bool:
        with self._lock:
            return all(
                [
                    batcher.is_empty()
                    for batcher in self._message_to_batcher_mapping.values()
                ]
            )

    def flush(self) -> None:
        with self._lock:
            for batcher in self._message_to_batcher_mapping.values():
                batcher.flush()

    def flush_ready(self) -> None:
        """Flush every batcher whose flush interval has elapsed.

        Invoked periodically by ``FlushingThread``. Holds the lock for the
        whole iteration so adds and flushes serialize against each other.
        Each batcher's flush is wrapped in its own try/except so a failure
        on one batcher does not skip the rest in the same tick.
        """
        with self._lock:
            for batcher in self._message_to_batcher_mapping.values():
                try:
                    if batcher.is_ready_to_flush():
                        batcher.flush()
                except Exception:
                    LOGGER.exception(
                        "Batcher flush failed; remaining batchers will still be checked."
                    )
