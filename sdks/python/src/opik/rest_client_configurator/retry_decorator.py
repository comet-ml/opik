import contextlib
import contextvars
from typing import Iterator

import tenacity
import httpx

from opik.rate_limit import rate_limit
from opik.rest_api.core import api_error as rest_api_error


RETRYABLE_STATUS_CODES = [429, 500, 502, 503, 504]

_caller_handles_rate_limits: contextvars.ContextVar[bool] = contextvars.ContextVar(
    "caller_handles_rate_limits", default=False
)


@contextlib.contextmanager
def rate_limits_handled_by_caller() -> Iterator[None]:
    """REST calls made inside this block raise a 429 that says when to retry
    straight away instead of retrying it, for callers that schedule their own
    retry. A 429 without that still gets the usual backoff."""
    token = _caller_handles_rate_limits.set(True)
    try:
        yield
    finally:
        _caller_handles_rate_limits.reset(token)


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
        if exception.status_code == 429 and _caller_handles_rate_limits.get():
            headers = exception.headers or {}
            if (
                rate_limit.parse_rate_limit(headers) is not None
                or rate_limit.parse_retry_after(headers) is not None
            ):
                return False
        if exception.status_code in RETRYABLE_STATUS_CODES:
            return True

    return False


def _wait_duration(retry_state: tenacity.RetryCallState) -> float:
    """Return how long to wait before the next attempt.

    For 429 responses, honour the server's ``Retry-After`` header (seconds or an
    HTTP date, at least ``rate_limit.MIN_RETRY_AFTER_SECONDS``) when it asks for
    less than ``rate_limit.MAX_RETRY_AFTER_SECONDS``.  Fall back to exponential
    backoff for all other cases.
    """
    exc = retry_state.outcome.exception() if retry_state.outcome else None
    if isinstance(exc, rest_api_error.ApiError) and exc.status_code == 429:
        seconds = rate_limit.parse_retry_after(exc.headers or {})
        # A longer wait would block the caller's request; back off normally instead.
        if seconds is not None and seconds < rate_limit.MAX_RETRY_AFTER_SECONDS:
            return seconds

    # Exponential backoff: ~5 s, ~6 s for the two possible retries.
    return tenacity.wait_exponential(multiplier=3, min=5, max=15)(retry_state)


opik_rest_retry = tenacity.retry(
    stop=tenacity.stop_after_attempt(3),
    wait=_wait_duration,
    retry=tenacity.retry_if_exception(_allowed_to_retry),
    reraise=True,
)
