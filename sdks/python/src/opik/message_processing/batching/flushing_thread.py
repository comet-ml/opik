import logging
import threading
import time
from typing import Callable

LOGGER = logging.getLogger(__name__)


class FlushingThread(threading.Thread):
    """Periodically invokes ``flush_callable`` until ``close()`` is called.

    Knows nothing about batchers or locks — those are the responsibility of
    whoever supplies the callable (e.g. ``BatchManager.flush_ready``).
    """

    def __init__(
        self,
        flush_callable: Callable[[], None],
        probe_interval_seconds: float = 0.1,
    ) -> None:
        threading.Thread.__init__(self, daemon=True)
        self._flush_callable = flush_callable
        self._probe_interval_seconds = probe_interval_seconds
        self._closed = False

    def close(self) -> None:
        self._closed = True

    def run(self) -> None:
        while not self._closed:
            try:
                self._flush_callable()
            except Exception:
                LOGGER.exception("FlushingThread tick failed; thread will continue.")
            time.sleep(self._probe_interval_seconds)
