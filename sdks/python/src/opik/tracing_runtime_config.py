from __future__ import annotations

import threading
from typing import Optional
from . import config


class TracingRuntimeConfig:
    def __init__(self) -> None:
        self._lock = threading.RLock()
        self._tracing_active: Optional[bool] = None

    def set_tracing_active(self, active: bool) -> None:
        with self._lock:
            self._tracing_active = active

    def reset_to_config_default(self) -> None:
        with self._lock:
            self._tracing_active = None

    def is_tracing_active(self) -> bool:
        with self._lock:
            if self._tracing_active is not None:
                return self._tracing_active

        try:
            enabled = not config.OpikConfig().track_disable
            self._tracing_active = enabled

        except Exception:
            return True

        return enabled


runtime_config = TracingRuntimeConfig()


def set_tracing_active(active: bool) -> None:
    runtime_config.set_tracing_active(active)


def is_tracing_active() -> bool:
    return runtime_config.is_tracing_active()


def reset_tracing_to_config_default() -> None:
    runtime_config.reset_to_config_default()
