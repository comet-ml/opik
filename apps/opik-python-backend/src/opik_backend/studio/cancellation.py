"""
Cancellation checker for Optimization Studio jobs.

Monitors a Redis key for cancellation signals from the Java backend.
When a cancellation is detected, it triggers a callback to terminate
the running optimization subprocess.
"""

import logging
import threading
from typing import Callable, Optional

from opik_backend.utils.redis_utils import get_redis_client

logger = logging.getLogger(__name__)


class CancellationChecker:
    """
    Checks for cancellation signals in Redis for a specific optimization.

    The Java backend sets a Redis key (opik:cancel:{optimization_id}) when
    a user requests cancellation. This class polls that key and triggers
    a callback when cancellation is detected.
    """

    CANCEL_KEY_PATTERN = "opik:cancel:{}"

    def __init__(self, optimization_id: str):
        self.optimization_id = optimization_id
        self.redis_client = get_redis_client()
        self._stop_event = threading.Event()
        self._thread: Optional[threading.Thread] = None

    def check_cancelled(self) -> bool:
        """Checks if a cancellation signal exists in Redis."""
        key = self.CANCEL_KEY_PATTERN.format(self.optimization_id)
        is_cancelled = self.redis_client.exists(key)
        if is_cancelled:
            logger.info(
                f"Cancellation signal detected for optimization '{self.optimization_id}'"
            )
        return bool(is_cancelled)

    def start_background_check(
        self, on_cancelled: Callable[[], None], interval_secs: int = 2
    ):
        """
        Starts a background thread to periodically check for cancellation.

        Args:
            on_cancelled: Callback function to invoke when cancellation is detected.
            interval_secs: How often to check for cancellation (default: 2 seconds).
        """
        if self._thread and self._thread.is_alive():
            logger.warning("Cancellation checker already running.")
            return

        logger.info(
            f"Starting background cancellation checker for optimization '{self.optimization_id}'"
        )
        self._stop_event.clear()

        def check_loop():
            while not self._stop_event.is_set():
                try:
                    if self.check_cancelled():
                        on_cancelled()
                        break
                except Exception as e:
                    logger.warning(f"Error checking cancellation status: {e}")
                self._stop_event.wait(interval_secs)
            logger.debug(
                f"Cancellation checker loop stopped for '{self.optimization_id}'"
            )

        self._thread = threading.Thread(target=check_loop, daemon=True)
        self._thread.start()

    def stop_background_check(self):
        """Stops the background cancellation checker thread."""
        if self._thread and self._thread.is_alive():
            logger.info(
                f"Stopping background cancellation checker for optimization '{self.optimization_id}'"
            )
            self._stop_event.set()
            self._thread.join(timeout=5)
            if self._thread.is_alive():
                logger.warning(
                    f"Cancellation checker thread for '{self.optimization_id}' did not stop gracefully."
                )
            self._thread = None
