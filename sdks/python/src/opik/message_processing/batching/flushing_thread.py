import threading
import time
from typing import List

from . import base_batcher


class FlushingThread(threading.Thread):
    def __init__(
        self,
        batchers: List[base_batcher.BaseBatcher],
        probe_interval: float = 0.1,
    ):
        threading.Thread.__init__(self, daemon=True)
        self._batchers = batchers
        self.probe_interval = probe_interval
        self.closed = False

    def close(self):
        for batcher in self._batchers:
            batcher.flush()

        self.closed = True

    def run(self):
        while not self.closed:
            for batcher in self._batchers:
                if batcher.is_ready_to_flush():
                    batcher.flush()
            time.sleep(self.probe_interval)
