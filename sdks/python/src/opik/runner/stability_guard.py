"""Stability guard — prevents infinite restart loops when the child process keeps crashing."""

import time


class StabilityGuard:
    def __init__(self, max_crashes: int = 3, window_seconds: float = 30.0) -> None:
        self._max_crashes = max_crashes
        self._window_seconds = window_seconds
        self._crash_times: list[float] = []

    def record_crash(self) -> None:
        self._crash_times.append(time.monotonic())

    def is_stable(self) -> bool:
        now = time.monotonic()
        cutoff = now - self._window_seconds
        recent = [t for t in self._crash_times if t > cutoff]
        self._crash_times = recent
        return len(recent) < self._max_crashes

    def reset(self) -> None:
        self._crash_times.clear()
