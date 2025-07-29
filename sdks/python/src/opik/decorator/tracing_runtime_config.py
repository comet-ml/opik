from __future__ import annotations

import threading
from typing import Optional


class TracingRuntimeConfig:
    def __init__(self) -> None:
        self._lock = threading.RLock()
        self._tracing_active: Optional[bool] = None
        self._cached_config_enabled: Optional[bool] = None

    def set_tracing_active(self, active: bool) -> None:
        with self._lock:
            self._tracing_active = active

    def reset_to_config_default(self) -> None:
        with self._lock:
            self._tracing_active = None

    def is_tracing_active(self) -> bool:
        """Return current tracing status.

        Evaluation order:

        1. If an explicit runtime override was set via ``set_tracing_active`` – return it.
        2. Otherwise read the current value from configuration **on every call** instead of
           relying on a cached value.  Tests frequently toggle the ``OPIK_TRACK_DISABLE``
           environment variable at runtime; recalculating ensures the change is respected
           without having to manually clear an internal cache between tests.
        """

        with self._lock:
            if self._tracing_active is not None:
                return self._tracing_active

        # Re-evaluate configuration on every call to respect dynamic env-var changes
        try:
            from .. import config as _config_module

            enabled = not _config_module.OpikConfig().track_disable
        except Exception:
            # If configuration fails to load, default to enabled so that tracing works.
            return True

        # No caching – just return the freshly computed value.
        return enabled


runtime_cfg = TracingRuntimeConfig()


def set_tracing_active(active: bool) -> None:
    runtime_cfg.set_tracing_active(active)


def is_tracing_active() -> bool:
    return runtime_cfg.is_tracing_active()


def reset_tracing_to_config_default() -> None:
    runtime_cfg.reset_to_config_default()
