"""Status manager for tracking optimization lifecycle."""

import logging
from contextlib import contextmanager
from typing import Any, Dict, Optional

import opik

logger = logging.getLogger(__name__)

# A status update is the only thing that moves a run off INITIALIZED, so a transient server-side
# failure must not leave the run stuck. The SDK only retries on an HTTP *response* of 5xx/429/408/409,
# and only when max_retries is supplied — so we opt in explicitly here. Note this does NOT cover
# connection-level failures (dropped connection mid-deploy); those still rely on the backend
# stalled-run reaper as the ultimate backstop (OPIK-7159).
STATUS_UPDATE_MAX_RETRIES = 3


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
        # Metadata queued by the optimization body to be sent with mark_completed.
        # Set via set_completion_metadata(); consumed (and cleared) by mark_completed().
        self._pending_metadata: Optional[Dict[str, Any]] = None

    def set_completion_metadata(self, metadata: Optional[Dict[str, Any]]) -> None:
        """Queue metadata to be forwarded to the backend on mark_completed.

        Call this inside the ``optimization_lifecycle`` body once the SDK result
        is available.  The metadata is picked up automatically by
        ``mark_completed()`` so callers do not have to thread it through
        manually.  Passing ``None`` (or never calling this) leaves existing
        behaviour unchanged — no metadata key is sent.

        Args:
            metadata: Dict to merge into the optimization record, e.g.
                ``{"scoring_health": {"failed_count": 2, "total_count": 10}}``.
        """
        self._pending_metadata = metadata

    def update_status(
        self, status: str, metadata: Optional[Dict[str, Any]] = None
    ) -> None:
        """Update optimization status in backend.

        Args:
            status: New status ("running", "completed", "error", etc.)
            metadata: Optional extra metadata to merge into the optimization record.
                When provided (e.g. ``{"scoring_health": {"failed_count": 2,
                "total_count": 10}}``), the payload is sent directly via the
                underlying HTTP client so it reaches the backend's ``metadata``
                column even before the typed SDK client exposes the field.
                When absent, falls back to the typed ``update_optimizations_by_id``
                call (preserves existing behaviour for callers that don't have
                metadata to attach).
        """
        logger.debug(
            f"Updating optimization {self.optimization_id} status to '{status}'"
        )

        if metadata:
            # The typed SDK client's update_optimizations_by_id does not yet
            # expose a ``metadata`` parameter, so we reach the underlying HTTP
            # client directly — same pattern the generated raw client uses
            # internally. This lets us include the metadata dict in the PUT body
            # without waiting for a full SDK re-generation.
            #
            # scoring_health is best-effort: the status transition is what moves
            # the run off RUNNING (and out of the stalled-run reaper's range), so
            # if the metadata-carrying PUT fails for ANY reason we fall back to
            # the typed status-only update rather than leave the run stuck.
            try:
                raw_client = self.client.rest_client.optimizations._raw_client
                client_wrapper = raw_client._client_wrapper
                response = client_wrapper.httpx_client.request(
                    f"v1/private/optimizations/{self.optimization_id}",
                    method="PUT",
                    json={
                        "status": status,
                        "metadata": metadata,
                    },
                    headers={"content-type": "application/json"},
                    request_options={"max_retries": STATUS_UPDATE_MAX_RETRIES},
                )
                # Unlike the typed wrapper, the raw client does NOT raise on a
                # non-2xx response — check explicitly so a rejected PUT is not
                # silently swallowed (which would leave the run looking done
                # while the backend never recorded the transition).
                status_code = getattr(response, "status_code", None)
                if status_code is not None and status_code >= 400:
                    raise RuntimeError(
                        f"metadata status update returned HTTP {status_code}"
                    )
            except Exception as metadata_error:
                logger.warning(
                    f"Failed to send status '{status}' with metadata for "
                    f"optimization {self.optimization_id} ({metadata_error}); "
                    "retrying status-only so the run still transitions.",
                )
                self.client.rest_client.optimizations.update_optimizations_by_id(
                    self.optimization_id,
                    status=status,
                    request_options={"max_retries": STATUS_UPDATE_MAX_RETRIES},
                )
        else:
            self.client.rest_client.optimizations.update_optimizations_by_id(
                self.optimization_id,
                status=status,
                request_options={"max_retries": STATUS_UPDATE_MAX_RETRIES},
            )

        logger.debug(
            f"Optimization {self.optimization_id} status updated to '{status}'"
        )

    def mark_running(self) -> None:
        """Mark optimization as running."""
        self.update_status("running")

    def mark_completed(self, metadata: Optional[Dict[str, Any]] = None) -> None:
        """Mark optimization as completed.

        Args:
            metadata: Optional metadata to persist alongside the status change
                (e.g. ``{"scoring_health": {"failed_count": 2, "total_count": 10}}``).
                Omit entirely (or pass ``None``) to leave existing metadata
                untouched — this keeps the call backwards-compatible with older
                SDK versions that do not attach scoring_health.  When
                ``set_completion_metadata`` was called earlier on this manager,
                its value is merged here (explicit ``metadata`` arg takes
                precedence when both are provided).
        """
        effective_metadata = metadata or self._pending_metadata
        self._pending_metadata = None  # consumed — reset for safety
        self.update_status("completed", metadata=effective_metadata)

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
    except Exception as e:
        logger.error(f"Optimization failed, marking as error: {e}")
        try:
            status_manager.mark_error()
        except Exception as status_error:
            logger.error(
                f"Failed to update status to 'error': {status_error}", exc_info=True
            )
        raise  # Re-raise the original exception
    else:
        # mark_completed is outside the try/except so a transient completion-callback
        # failure (network blip, Opik-key expiry) does NOT flip a successfully-finished
        # run to ERROR. If mark_completed raises, the run stays RUNNING and the backend
        # stalled-run reaper will eventually move it to ERROR (OPIK-7159 backstop).
        try:
            status_manager.mark_completed()
        except Exception as completed_error:
            logger.error(
                f"Failed to update status to 'completed': {completed_error}",
                exc_info=True,
            )
    finally:
        status_manager.close()
