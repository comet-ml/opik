import tenacity
import httpx

from opik.rest_api.core import api_error as rest_api_error


RETRYABLE_STATUS_CODES = [500, 502, 503, 504]


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


opik_rest_retry = tenacity.retry(
    stop=tenacity.stop_after_attempt(3),
    wait=tenacity.wait_exponential(multiplier=3, min=5, max=15),  # 5, 6, 12
    retry=tenacity.retry_if_exception(_allowed_to_retry),
)
