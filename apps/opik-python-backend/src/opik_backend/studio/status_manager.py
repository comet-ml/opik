"""Status manager for tracking optimization lifecycle."""

import logging
import traceback
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

    def update_status(self, status: str, error_info: Optional[dict] = None) -> None:
        """Update optimization status in backend.

        Args:
            status: New status ("running", "completed", "error", etc.)
            error_info: Optional structured failure reason to persist alongside
                an "error" status. A dict shaped like
                ``{"exception_type", "message", "traceback"}`` (matching the
                backend ``ErrorInfo`` type used by spans/traces). An empty/None
                dict is treated as "no reason" and is NOT sent, so it can't
                clobber a reason persisted by an earlier update.
        """
        logger.debug(
            f"Updating optimization {self.optimization_id} status to '{status}'"
        )
        body = {"status": status}
        # Only persist a non-empty reason: sending nothing avoids overwriting a
        # previously-stored error_info (the REST update gates on `is not None`,
        # not on emptiness). Truncate the free-text fields so we don't push
        # oversized tracebacks into ClickHouse.
        if error_info:
            truncated = dict(error_info)
            for key in ("message", "traceback"):
                value = truncated.get(key)
                if isinstance(value, str):
                    truncated[key] = value[:MAX_ERROR_INFO_LENGTH]
            body["error_info"] = truncated
        self._send_update(body)
        logger.debug(
            f"Optimization {self.optimization_id} status updated to '{status}'"
        )

    def _send_update(self, body: dict) -> None:
        """Persist an optimization update, tolerating an older opik SDK.

        The python-backend pins a released ``opik`` whose typed
        ``update_optimizations_by_id`` may predate the ``error_info`` field
        (added in the monorepo SDK, not yet in the pinned release). When the
        typed call rejects the ``error_info`` kwarg, fall back to the SDK's
        pre-configured httpx client so the reason still reaches the backend
        (it accepts snake_case fields and ignores unknown ones). Without this,
        a build-time failure marks ``error`` via a call that raises
        ``TypeError`` — swallowed by ``optimization_lifecycle`` — leaving the
        run stuck at ``running``. Once the SDK ships ``error_info``, the typed
        call handles it and this fallback is never exercised.
        """
        optimizations = self.client.rest_client.optimizations
        try:
            optimizations.update_optimizations_by_id(self.optimization_id, **body)
            return
        except TypeError:
            logger.debug(
                "Installed opik SDK lacks the 'error_info' update field; "
                "sending the optimization update via the raw REST client."
            )
        optimizations._raw_client._client_wrapper.httpx_client.request(
            f"v1/private/optimizations/{self.optimization_id}",
            method="PUT",
            json=body,
        ).raise_for_status()

    def mark_running(self) -> None:
        """Mark optimization as running."""
        self.update_status("running")

    def mark_completed(self) -> None:
        """Mark optimization as completed."""
        self.update_status("completed")

    def mark_error(self, error_info: Optional[dict] = None) -> None:
        """Mark optimization as failed.

        Args:
            error_info: Optional structured failure reason (dict shaped like
                ``{"exception_type", "message", "traceback"}``) to persist as
                the optimization's error_info.
        """
        self.update_status("error", error_info=error_info)

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
            status_manager.mark_error(
                {
                    "exception_type": type(e).__name__,
                    "message": str(e),
                    "traceback": traceback.format_exc(),
                }
            )
        except Exception as status_error:
            logger.error(
                f"Failed to update status to 'error': {status_error}", exc_info=True
            )
        raise  # Re-raise the original exception
    finally:
        status_manager.close()
