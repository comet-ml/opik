"""Status manager for tracking optimization lifecycle."""

import logging
from contextlib import contextmanager
from typing import Optional

import opik

logger = logging.getLogger(__name__)


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
    
    def update_status(self, status: str) -> None:
        """Update optimization status in backend.
        
        Args:
            status: New status ("running", "completed", "error", etc.)
        """
        logger.info(f"Updating optimization {self.optimization_id} status to '{status}'")
        self.client.rest_client.optimizations.update_optimizations_by_id(
            self.optimization_id,
            status=status
        )
        logger.info(f"Optimization {self.optimization_id} status updated to '{status}'")
    
    def mark_running(self) -> None:
        """Mark optimization as running."""
        self.update_status("running")
    
    def mark_completed(self) -> None:
        """Mark optimization as completed."""
        self.update_status("completed")
    
    def mark_error(self) -> None:
        """Mark optimization as failed."""
        self.update_status("error")

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
            status_manager.mark_error()
        except Exception as status_error:
            logger.error(
                f"Failed to update status to 'error': {status_error}",
                exc_info=True
            )
        raise  # Re-raise the original exception
    finally:
        status_manager.close()

