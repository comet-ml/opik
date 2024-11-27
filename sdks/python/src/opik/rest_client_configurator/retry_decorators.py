import tenacity
import httpx

connection_retry = tenacity.retry(
    stop=tenacity.stop_after_attempt(3),
    wait=tenacity.wait_exponential(multiplier=1, min=1, max=10),
    retry=tenacity.retry_if_exception_type(
        (
            httpx.RemoteProtocolError,  # handle retries for expired connections
            httpx.ConnectError,
            httpx.ConnectTimeout,
        )
    ),
)
