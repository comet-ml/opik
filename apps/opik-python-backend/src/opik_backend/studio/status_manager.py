"""Status manager for tracking optimization lifecycle."""

import logging
from contextlib import contextmanager
from typing import Optional

import opik

logger = logging.getLogger(__name__)

# Cap the persisted error message so we don't push oversized tracebacks into ClickHouse.
MAX_ERROR_INFO_LENGTH = 4000


class OptimizationStatusManager:
    """Manages optimization status updates.

    Centralizes all status update logic to ensure consistency
    and make it easy to add hooks or metrics later.
    """

    def __init__(self, client: opik.Opik, optimization_id: str):
        """Initialize status manager.

        Args:
            client: Opik client instance
            optimization_id: Optimization ID to manage
        """
        self.client = client
        self.optimization_id = optimization_id

    def update_status(self, status: str, error_info: Optional[str] = None) -> None:
        """Update optimization status in backend.

        Args:
            status: New status ("running", "completed", "error", etc.)
            error_info: Optional failure reason to persist alongside an "error"
                status. A blank/empty message is treated as "no reason" and is
                NOT sent, so it can't clobber a reason persisted by an earlier
                update (e.g. an exception whose ``str(e)`` is "").
        """
        logger.debug(f"Updating optimization {self.optimization_id} status to '{status}'")
        kwargs = {"status": status}
        # Only persist a non-blank reason: an empty string would overwrite a
        # previously-stored error_info with "" (the REST update gates on
        # `is not None`, not on emptiness).
        if error_info is not None and error_info.strip():
            kwargs["error_info"] = error_info[:MAX_ERROR_INFO_LENGTH]
        self.client.rest_client.optimizations.update_optimizations_by_id(
            self.optimization_id,
            **kwargs,
        )
        logger.debug(f"Optimization {self.optimization_id} status updated to '{status}'")

    def mark_running(self) -> None:
        """Mark optimization as running."""
        self.update_status("running")

    def mark_completed(self) -> None:
        """Mark optimization as completed."""
        self.update_status("completed")

    def mark_error(self, message: Optional[str] = None) -> None:
        """Mark optimization as failed.

        Args:
            message: Optional failure reason to persist as the optimization's error_info.
        """
        self.update_status("error", error_info=message)

    def close(self) -> None:
        """Close the Opik client and release resources."""
        try:
            self.client.end()
            logger.debug(f"Opik client closed for optimization {self.optimization_id}")
        except Exception as e:
            logger.warning(f"Failed to close Opik client: {e}")


@contextmanager
def optimization_lifecycle(status_manager: OptimizationStatusManager):
    """Context manager for optimization lifecycle with automatic status management.
    
    Ensures that optimization status is always updated correctly:
    - Sets status to 'running' when entering
    - Sets status to 'completed' on success
    - Sets status to 'error' on any exception
    
    Usage:
        with optimization_lifecycle(status_manager):
            # Do optimization work
            # Status automatically updated on success or failure
    
    Args:
        status_manager: Status manager instance
        
    Yields:
        The status manager (for additional status operations if needed)
        
    Raises:
        Any exception that occurs during optimization (after setting status to 'error')
    """
    try:
        status_manager.mark_running()
        yield status_manager
        status_manager.mark_completed()
    except Exception as e:
        logger.error(f"Optimization failed, marking as error: {e}")
        try:
            status_manager.mark_error(str(e))
        except Exception as status_error:
            logger.error(
                f"Failed to update status to 'error': {status_error}",
                exc_info=True
            )
        raise  # Re-raise the original exception
    finally:
        status_manager.close()

