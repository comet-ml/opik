import threading


class ThreadSafeCounter:
    """Thread-safe counter for tracking invocations in concurrent tests."""

    def __init__(self) -> None:
        self._value = 0
        self._lock = threading.Lock()

    def increment(self) -> int:
        """Increment and return the new value (1-based)."""
        with self._lock:
            self._value += 1
            return self._value

    @property
    def value(self) -> int:
        with self._lock:
            return self._value
