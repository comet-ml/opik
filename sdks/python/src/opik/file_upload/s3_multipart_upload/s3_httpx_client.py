import functools

import httpx
import tenacity

KEEPALIVE_EXPIRY_SECONDS = 10
CONNECT_TIMEOUT_SECONDS = 20
READ_TIMEOUT_SECONDS = 100
WRITE_TIMEOUT_SECONDS = 100
POOL_TIMEOUT_SECONDS = 20

RETRYABLE_STATUS_CODES = [500]


@functools.lru_cache
def get_cached() -> httpx.Client:
    return get()


def get() -> httpx.Client:
    limits = httpx.Limits(keepalive_expiry=KEEPALIVE_EXPIRY_SECONDS)

    timeout = httpx.Timeout(
        connect=CONNECT_TIMEOUT_SECONDS,
        read=READ_TIMEOUT_SECONDS,
        write=WRITE_TIMEOUT_SECONDS,
        pool=POOL_TIMEOUT_SECONDS,
    )

    return httpx.Client(limits=limits, timeout=timeout)


def _allowed_to_retry(exception: Exception) -> bool:
    if isinstance(
        exception,
        (
            httpx.ConnectError,
            httpx.TimeoutException,
        ),
    ):
        return True

    if isinstance(exception, httpx.HTTPStatusError):
        if exception.response.status_code in RETRYABLE_STATUS_CODES:
            return True

    return False


s3_retry = tenacity.retry(
    stop=tenacity.stop_after_attempt(3),
    wait=tenacity.wait_exponential(multiplier=5, min=10, max=45),
    retry=tenacity.retry_if_exception(_allowed_to_retry),
)
