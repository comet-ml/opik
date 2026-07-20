"""Status manager for tracking optimization lifecycle."""

import logging
import traceback
from contextlib import contextmanager
from typing import Any, Dict, Optional

import opik

logger = logging.getLogger(__name__)

# Cap the persisted error message so we don't push oversized tracebacks into ClickHouse.
MAX_ERROR_INFO_LENGTH = 4000

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
        self,
        status: str,
        error_info: Optional[dict] = None,
        metadata: Optional[Dict[str, Any]] = None,
    ) -> None:
        """Update optimization status in backend.

        Args:
            status: New status ("running", "completed", "error", etc.)
            error_info: Optional structured failure reason to persist alongside
                an "error" status. A dict shaped like
                ``{"exception_type", "message", "traceback"}`` (matching the
                backend ``ErrorInfo`` type used by spans/traces). An empty/None
                dict is treated as "no reason" and is NOT sent, so it can't
                clobber a reason persisted by an earlier update.
            metadata: Optional extra metadata to merge into the optimization record.
                When provided (e.g. ``{"scoring_health": {"failed_count": 2,
                "total_count": 10}}``), it reaches the backend's ``metadata``
                column. When absent, the existing metadata is preserved.
        """
        logger.debug(
            f"Updating optimization {self.optimization_id} status to '{status}'"
        )

        body: Dict[str, Any] = {"status": status}

        # Only persist a non-empty reason: sending nothing avoids overwriting a
        # previously-stored error_info (the REST update gates on `is not None`,
        # not on emptiness). Truncate the free-text fields so we don't push
        # oversized tracebacks into ClickHouse. Works on a copy — the caller's
        # dict is never mutated.
        if error_info:
            truncated = dict(error_info)

            # The exception message is short and most meaningful at its start —
            # keep the head.
            message = truncated.get("message")
            if isinstance(message, str) and len(message) > MAX_ERROR_INFO_LENGTH:
                truncated["message"] = message[:MAX_ERROR_INFO_LENGTH]

            # A traceback's most diagnostic frames (the innermost frame and the
            # actual raise site) are at the END, so keep the TAIL and mark that
            # earlier frames were dropped. Keeping the head would discard exactly
            # the frames closest to the failure (OPIK-7172 surfaces this reason).
            traceback_str = truncated.get("traceback")
            if (
                isinstance(traceback_str, str)
                and len(traceback_str) > MAX_ERROR_INFO_LENGTH
            ):
                marker = "...[traceback truncated]...\n"
                truncated["traceback"] = (
                    marker + traceback_str[-(MAX_ERROR_INFO_LENGTH - len(marker)):]
                )

            body["error_info"] = truncated

        if metadata is not None:
            body["metadata"] = metadata

        self._send_update(body)

        logger.debug(
            f"Optimization {self.optimization_id} status updated to '{status}'"
        )

    def _send_update(self, body: dict) -> None:
        """Persist an optimization update, tolerating an older opik SDK.

        The python-backend pins a released ``opik`` whose typed
        ``update_optimizations_by_id`` may predate the ``error_info`` /
        ``metadata`` fields (added in the monorepo SDK, not yet in the pinned
        release). So when the body carries anything beyond ``status`` we reach
        the SDK's pre-configured httpx client directly — it accepts snake_case
        fields and ignores unknown ones — with explicit retries.

        scoring metadata and the failure reason are best-effort: the status
        transition is what moves the run off RUNNING/INITIALIZED (and out of the
        stalled-run reaper's range), so if the enriched PUT fails for ANY reason
        we fall back to a typed status-only update rather than leave the run
        stuck (OPIK-7159 backstop).
        """
        status = body["status"]
        # More than just "status" means we're carrying error_info and/or metadata.
        enriched = len(body) > 1

        if enriched:
            try:
                raw_client = self.client.rest_client.optimizations._raw_client
                client_wrapper = raw_client._client_wrapper
                response = client_wrapper.httpx_client.request(
                    f"v1/private/optimizations/{self.optimization_id}",
                    method="PUT",
                    json=body,
                    headers={"content-type": "application/json"},
                    request_options={"max_retries": STATUS_UPDATE_MAX_RETRIES},
                )
                # Unlike the typed wrapper, the raw client does NOT raise on a
                # non-2xx response — check explicitly so a rejected PUT is not
                # silently swallowed (which would leave the run looking done
                # while the backend never recorded the transition). A missing
                # status_code (unexpected wrapper shape) is treated as failure
                # too, so we always fall back rather than assume success.
                status_code = getattr(response, "status_code", None)
                if status_code is None or status_code >= 400:
                    raise RuntimeError(
                        f"enriched status update returned HTTP {status_code}"
                    )
                return
            except Exception as update_error:
                logger.warning(
                    f"Failed to send status '{status}' with error_info/metadata for "
                    f"optimization {self.optimization_id} ({update_error}); "
                    "retrying status-only so the run still transitions.",
                )

        self.client.rest_client.optimizations.update_optimizations_by_id(
            self.optimization_id,
            status=status,
            request_options={"max_retries": STATUS_UPDATE_MAX_RETRIES},
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
