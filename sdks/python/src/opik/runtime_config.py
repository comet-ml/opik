"""
Runtime configuration for Opik tracing.

This module enables dynamic enabling or disabling of Opik tracing at runtime
without requiring application restarts or code edits. It acts as a thin,
thread-safe wrapper around a global flag, falling back to the static
configuration (`OpikConfig.track_disable`) when no runtime override is set.
"""

from __future__ import annotations

import threading
from typing import Optional


class _RuntimeConfig:
    """Thread-safe runtime configuration manager for Opik tracing state."""

    def __init__(self) -> None:
        self._lock = threading.RLock()
        self._tracing_active: Optional[bool] = None  # None ⇒ fallback to config

    # ---------------------------------------------------------------------
    # Public helpers
    # ---------------------------------------------------------------------
    def set_tracing_active(self, active: bool) -> None:
        """Override global tracing state.

        Args:
            active: ``True`` to enable tracing, ``False`` to disable.
        """
        with self._lock:
            self._tracing_active = active

    def reset_to_config_default(self) -> None:
        """Clear runtime override so static config is respected again."""
        with self._lock:
            self._tracing_active = None

    def is_tracing_active(self) -> bool:
        """Return current tracing state.

        If a runtime override was set via :func:`set_tracing_active`, that value
        is returned. Otherwise, we fall back to ``not OpikConfig.track_disable``.
        If the configuration cannot be loaded for any reason, tracing remains
        enabled by default (fail open).
        """
        with self._lock:
            if self._tracing_active is not None:
                return self._tracing_active

        # Fallback lookup happens outside the lock to avoid import deadlocks
        try:
            from . import config as _config_module  # local import to avoid cycles

            cfg = _config_module.OpikConfig()
            return not cfg.track_disable
        except Exception:  # noqa: BLE001
            # In the unlikely event that config fails to load, keep tracing on to
            # avoid silently dropping data.
            return True


# -------------------------------------------------------------------------
# Module-level helpers (public API)
# -------------------------------------------------------------------------

_runtime_cfg = _RuntimeConfig()


def set_tracing_active(active: bool) -> None:
    """Enable or disable Opik tracing globally at runtime.

    Example
    -------
    >>> import opik
    >>> opik.set_tracing_active(False)  # Disable tracing
    >>> opik.set_tracing_active(True)   # Re-enable tracing
    """
    _runtime_cfg.set_tracing_active(active)


def is_tracing_active() -> bool:  # noqa: D401 – simple predicate
    """Return ``True`` if tracing is currently active."""
    return _runtime_cfg.is_tracing_active()


def reset_tracing_to_config_default() -> None:
    """Reset runtime override so the static ``OpikConfig.track_disable`` wins."""
    _runtime_cfg.reset_to_config_default()
