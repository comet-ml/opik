import logging
import threading
import time
from typing import List

from . import base_batcher

LOGGER = logging.getLogger(__name__)


class FlushingThread(threading.Thread):
    def __init__(
        self,
        batchers: List[base_batcher.BaseBatcher],
        lock: threading.RLock,
        probe_interval_seconds: float = 0.1,
    ) -> None:
        threading.Thread.__init__(self, daemon=True)
        self._batchers = batchers
        self._lock = lock
        self._probe_interval_seconds = probe_interval_seconds
        self._closed = False

    def close(self) -> None:
        self._closed = True

    def run(self) -> None:
        while not self._closed:
            try:
                with self._lock:
                    for batcher in self._batchers:
                        if batcher.is_ready_to_flush():
                            batcher.flush()
            except Exception:
                LOGGER.exception(
                    "FlushingThread iteration failed; thread will continue."
                )
            time.sleep(self._probe_interval_seconds)
