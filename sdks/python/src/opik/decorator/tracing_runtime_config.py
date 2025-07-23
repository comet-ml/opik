from __future__ import annotations

import threading
from typing import Optional


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
            from .. import config as _config_module

            cfg = _config_module.OpikConfig()
            return not cfg.track_disable
        except Exception:
            return True


_runtime_cfg = TracingRuntimeConfig()


def set_tracing_active(active: bool) -> None:
    _runtime_cfg.set_tracing_active(active)


def is_tracing_active() -> bool:
    return _runtime_cfg.is_tracing_active()


def reset_tracing_to_config_default() -> None:
    _runtime_cfg.reset_to_config_default()
