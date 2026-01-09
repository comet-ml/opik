"""
Cancellation monitor for Optimization Studio jobs.

Uses a single background thread to monitor cancellation signals for all
running optimizations via Redis MGET (multi-get). This is more efficient
than having a separate polling thread per optimization.

Architecture:
    - CancellationMonitor: Singleton that polls Redis for all registered optimizations
    - CancellationHandle: Per-optimization handle for registration and status checking

Environment Variables (for self-hosted deployments):
    OPTSTUDIO_CANCEL_POLL_INTERVAL_SECS: Polling interval in seconds (default: 2)
"""

import logging
import os
import threading
from typing import Any, Callable, Dict, Optional, Type

from opik_backend.utils.redis_utils import get_redis_client

logger = logging.getLogger(__name__)

# Redis key pattern for cancellation signals
CANCEL_KEY_PATTERN = "opik:cancel:{}"

# Environment variable for polling interval
ENV_CANCEL_POLL_INTERVAL_SECS = "OPTSTUDIO_CANCEL_POLL_INTERVAL_SECS"

# Default polling interval
DEFAULT_POLL_INTERVAL_SECS = 2


class CancellationMonitor:
    """
    Singleton monitor that checks cancellation status for all running optimizations.
    
    Uses a single thread with Redis MGET to efficiently check multiple keys at once.
    Optimizations register themselves and provide a callback for when cancelled.
    """
    
    _instance: Optional["CancellationMonitor"] = None
    _lock: threading.Lock = threading.Lock()
    
    def __new__(cls) -> "CancellationMonitor":
        with cls._lock:
            if cls._instance is None:
                cls._instance = super().__new__(cls)
                cls._instance._initialized = False
            return cls._instance
    
    def __init__(self) -> None:
        if self._initialized:
            return
        
        self._registrations: Dict[str, Callable[[], None]] = {}
        self._registrations_lock = threading.Lock()
        self._stop_event = threading.Event()
        self._thread: Optional[threading.Thread] = None
        self._poll_interval = float(os.getenv(
            ENV_CANCEL_POLL_INTERVAL_SECS,
            str(DEFAULT_POLL_INTERVAL_SECS)
        ))
        self._initialized = True
        
        logger.info(f"CancellationMonitor initialized (poll interval: {self._poll_interval}s)")
    
    def register(self, optimization_id: str, on_cancelled: Callable[[], None]) -> None:
        """
        Register an optimization for cancellation monitoring.
        
        Args:
            optimization_id: The optimization ID to monitor
            on_cancelled: Callback to invoke when cancellation is detected
        """
        with self._registrations_lock:
            self._registrations[optimization_id] = on_cancelled
            logger.debug(f"Registered optimization '{optimization_id}' for cancellation monitoring")
            
            # Start monitor thread if not running
            if self._thread is None or not self._thread.is_alive():
                self._start_monitor()
    
    def unregister(self, optimization_id: str) -> None:
        """
        Unregister an optimization from cancellation monitoring.
        
        Args:
            optimization_id: The optimization ID to stop monitoring
        """
        with self._registrations_lock:
            if optimization_id in self._registrations:
                del self._registrations[optimization_id]
                logger.debug(f"Unregistered optimization '{optimization_id}' from cancellation monitoring")
            
            # Stop monitor thread if no more registrations
            if not self._registrations and self._thread and self._thread.is_alive():
                self._stop_monitor()
    
    def _start_monitor(self) -> None:
        """Start the background monitoring thread."""
        self._stop_event.clear()
        self._thread = threading.Thread(target=self._monitor_loop, daemon=True, name="CancellationMonitor")
        self._thread.start()
        logger.info("CancellationMonitor thread started")
    
    def _stop_monitor(self) -> None:
        """Stop the background monitoring thread."""
        self._stop_event.set()
        if self._thread:
            self._thread.join(timeout=5)
            if self._thread.is_alive():
                logger.warning("CancellationMonitor thread did not stop gracefully")
            self._thread = None
        logger.info("CancellationMonitor thread stopped")
    
    def _monitor_loop(self) -> None:
        """Main monitoring loop - checks all registered optimizations via MGET."""
        redis_client = get_redis_client()
        
        while not self._stop_event.is_set():
            try:
                with self._registrations_lock:
                    if not self._registrations:
                        # No registrations, exit loop
                        break
                    
                    # Get all optimization IDs and their callbacks
                    opt_ids = list(self._registrations.keys())
                    callbacks = dict(self._registrations)
                
                if opt_ids:
                    # Build keys for MGET
                    keys = [CANCEL_KEY_PATTERN.format(opt_id) for opt_id in opt_ids]
                    
                    # Single MGET call for all keys
                    results = redis_client.mget(keys)
                    
                    # Process results
                    for opt_id, key, value in zip(opt_ids, keys, results):
                        if value is not None:
                            # Cancellation detected
                            logger.info(f"Cancellation signal detected for optimization '{opt_id}'")
                            
                            # Get and invoke callback
                            callback = callbacks.get(opt_id)
                            if callback:
                                try:
                                    callback()
                                except Exception as e:
                                    logger.error(f"Error in cancellation callback for '{opt_id}': {e}")
                            
                            # Clean up the key
                            try:
                                redis_client.delete(key)
                                logger.debug(f"Cleaned up cancellation key for '{opt_id}'")
                            except Exception as e:
                                logger.warning(f"Error cleaning up cancel key for '{opt_id}': {e}")
                            
                            # Remove from registrations (don't call unregister to avoid potential deadlock)
                            with self._registrations_lock:
                                self._registrations.pop(opt_id, None)
                
            except Exception as e:
                logger.warning(f"Error in cancellation monitor loop: {e}", exc_info=True)
            
            # Wait for next poll interval
            self._stop_event.wait(self._poll_interval)
        
        logger.debug("CancellationMonitor loop exited")


def get_cancellation_monitor() -> CancellationMonitor:
    """Get the global CancellationMonitor singleton instance."""
    return CancellationMonitor()


class CancellationHandle:
    """
    Handle for a single optimization's cancellation status.
    
    Registers with the global CancellationMonitor and provides a simple
    interface for checking cancellation status.
    
    Usage:
        with CancellationHandle(optimization_id, on_cancelled=my_callback) as handle:
            # ... do work ...
            if handle.was_cancelled:
                # Handle cancellation
    """
    
    def __init__(self, optimization_id: str, on_cancelled: Optional[Callable[[], None]] = None) -> None:
        self.optimization_id: str = optimization_id
        self._cancelled_event: threading.Event = threading.Event()
        self._on_cancelled: Optional[Callable[[], None]] = on_cancelled
        self._registered: bool = False
    
    def __enter__(self) -> "CancellationHandle":
        """Context manager entry - registers with monitor."""
        self.register()
        return self
    
    def __exit__(
        self,
        exc_type: Optional[Type[BaseException]],
        exc_val: Optional[BaseException],
        exc_tb: Optional[Any],
    ) -> bool:
        """Context manager exit - unregisters from monitor."""
        self.unregister()
        return False  # Don't suppress exceptions
    
    @property
    def was_cancelled(self) -> bool:
        """Thread-safe check if cancellation was triggered."""
        return self._cancelled_event.is_set()
    
    def register(self) -> None:
        """Register this optimization for cancellation monitoring."""
        if self._registered:
            return
        
        def on_cancelled_wrapper():
            self._cancelled_event.set()
            if self._on_cancelled:
                self._on_cancelled()
        
        get_cancellation_monitor().register(self.optimization_id, on_cancelled_wrapper)
        self._registered = True
        logger.debug(f"CancellationHandle registered for '{self.optimization_id}'")
    
    def unregister(self) -> None:
        """Unregister this optimization from cancellation monitoring."""
        if not self._registered:
            return
        
        get_cancellation_monitor().unregister(self.optimization_id)
        self._registered = False
        logger.debug(f"CancellationHandle unregistered for '{self.optimization_id}'")
