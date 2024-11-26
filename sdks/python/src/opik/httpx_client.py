from typing import Optional, Dict, Any
import httpx

from . import hooks, package_version
import platform
import tenacity


class RetryingClient(httpx.Client):
    @tenacity.retry(
        stop=tenacity.stop_after_attempt(3),  # Retry up to 3 times
        wait=tenacity.wait_exponential(
            multiplier=1, min=1, max=10
        ),  # Exponential backoff
        retry=tenacity.retry_if_exception_type(
            (
                httpx.RemoteProtocolError,  # handle retries for expired connections
                httpx.ConnectError,
                httpx.ConnectTimeout,
            )
        ),
    )
    def request(  # type: ignore
        self,
        *args,
        **kwargs,
    ):
        return super().request(*args, **kwargs)


def get(workspace: str, api_key: Optional[str]) -> httpx.Client:
    limits = httpx.Limits(keepalive_expiry=30)
    timeout = httpx.Timeout(
        connect=5.0,  # Time to establish a connection
        read=5,  # Time to wait for a response after request
        write=5,  # Time to send data to the server
        pool=60.0,  # Time a connection can remain idle in the pool
    )

    client = RetryingClient(limits=limits, timeout=timeout)

    headers = _prepare_headers(workspace=workspace, api_key=api_key)
    client.headers.update(headers)

    hooks.httpx_client_hook(client)

    return client


def _prepare_headers(workspace: str, api_key: Optional[str]) -> Dict[str, Any]:
    result = {
        "Comet-Workspace": workspace,
        "X-OPIK-DEBUG-SDK-VERSION": package_version.VERSION,
        "X-OPIK-DEBUG-PY-VERSION": platform.python_version(),
    }

    if api_key is not None:
        result["Authorization"] = api_key

    return result
