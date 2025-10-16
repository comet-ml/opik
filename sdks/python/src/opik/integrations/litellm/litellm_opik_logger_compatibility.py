"""
Compatibility layer to prevent duplicate logging when using both track_litellm()
and LiteLLM's OpikLogger callback.

This module patches OpikLogger to become a no-op when track_litellm() is active,
ensuring order-independent behavior.
"""

import logging

from typing import Any, Callable, Dict

import litellm

try:
    import litellm.integrations.opik.opik as opik_logger
except ImportError:
    opik_logger = None

LOGGER = logging.getLogger(__name__)

_ORIGINAL_OPIK_LOGGER_METHODS: Dict[str, Callable[..., Any]] = {}


def _is_track_litellm_active() -> bool:
    """Check if track_litellm() has been activated."""

    return hasattr(litellm, "opik_tracked") and litellm.opik_tracked


def _create_noop_method(method_name: str) -> Callable[..., Any]:
    """Create a no-op method that logs a debug message when called."""

    def noop_method(self: Any, *args: Any, **kwargs: Any) -> Any:
        if _is_track_litellm_active():
            LOGGER.debug(
                "OpikLogger.%s called but ignored because track_litellm() is active. "
                "Using decorator-based integration instead.",
                method_name,
            )
            return None
        else:
            # If track_litellm is not active, call the original method
            original_method = _ORIGINAL_OPIK_LOGGER_METHODS.get(method_name)
            if original_method:
                return original_method(self, *args, **kwargs)
            return None

    return noop_method


def disable_opik_logger_when_decorator_active() -> None:
    """
    Patch OpikLogger methods to become no-ops when track_litellm() is active.

    This ensures order-independent behavior - OpikLogger won't log anything
    whenever track_litellm() has been called, regardless of when OpikLogger
    instances are created or added to callbacks.
    """
    if opik_logger is None:
        return

    # Only patch once
    if _ORIGINAL_OPIK_LOGGER_METHODS:
        return

    # Store original methods and patch them
    methods_to_patch = [
        "log_success_event",
        "log_failure_event",
        "async_log_success_event",
        "async_log_failure_event",
    ]

    for method_name in methods_to_patch:
        if hasattr(opik_logger.OpikLogger, method_name):
            original_method = getattr(opik_logger.OpikLogger, method_name)
            _ORIGINAL_OPIK_LOGGER_METHODS[method_name] = original_method
            setattr(
                opik_logger.OpikLogger, method_name, _create_noop_method(method_name)
            )

    LOGGER.info(
        "Patched OpikLogger to disable logging when track_litellm() is active. "
        "This prevents duplicate logging."
    )
