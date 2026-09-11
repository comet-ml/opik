import tenacity
import httpx

from opik.rest_api.core import api_error as rest_api_error


RETRYABLE_STATUS_CODES = [429, 500, 502, 503, 504]

# Upper bound on how long we will sleep when honouring a Retry-After header.
_MAX_RETRY_AFTER_SECONDS = 60.0


def _allowed_to_retry(exception: Exception) -> bool:
    if isinstance(
        exception,
        (
            httpx.RemoteProtocolError,  # handle retries for expired connections
            httpx.ConnectError,
            httpx.TimeoutException,
        ),
    ):
        return True

    if isinstance(exception, rest_api_error.ApiError):
        if exception.status_code in RETRYABLE_STATUS_CODES:
            return True

    return False


def _wait_duration(retry_state: tenacity.RetryCallState) -> float:
    """Return how long to wait before the next attempt.

    For 429 responses, honour the server's ``Retry-After`` header when present
    (capped at ``_MAX_RETRY_AFTER_SECONDS``).  Fall back to exponential backoff
    for all other cases.
    """
    exc = retry_state.outcome.exception() if retry_state.outcome else None
    if isinstance(exc, rest_api_error.ApiError) and exc.status_code == 429:
        headers = exc.headers or {}
        raw = headers.get("retry-after") or headers.get("Retry-After")
        if raw is not None:
            try:
                seconds = float(raw)
                if 0 <= seconds <= _MAX_RETRY_AFTER_SECONDS:
                    return seconds
            except (ValueError, TypeError):
                pass

    # Exponential backoff: ~5 s, ~6 s for the two possible retries.
    return tenacity.wait_exponential(multiplier=3, min=5, max=15)(retry_state)


opik_rest_retry = tenacity.retry(
    stop=tenacity.stop_after_attempt(3),
    wait=_wait_duration,
    retry=tenacity.retry_if_exception(_allowed_to_retry),
    reraise=True,
)
