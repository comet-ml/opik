import threading
import time
from typing import List

from . import base_batcher


class FlushingThread(threading.Thread):
    def __init__(
        self,
        batchers: List[base_batcher.BaseBatcher],
        probe_interval_seconds: float = 0.1,
    ) -> None:
        threading.Thread.__init__(self, daemon=True)
        self._batchers = batchers
        self._probe_interval_seconds = probe_interval_seconds
        self._closed = False

    def close(self) -> None:
        for batcher in self._batchers:
            batcher.flush()

        self._closed = True

    def run(self) -> None:
        while not self._closed:
            for batcher in self._batchers:
                if batcher.is_ready_to_flush():
                    batcher.flush()
            time.sleep(self._probe_interval_seconds)
