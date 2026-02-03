import logging
from typing import NamedTuple, Optional

import httpx

from opik import httpx_client, url_helpers


LOGGER = logging.getLogger(__name__)


class ProbeResult(NamedTuple):
    is_healthy: bool
    error_message: Optional[str]


class ConnectionProbe:
    """
    Provides functionality to probe and verify the connection to a remote server.

    This class is designed to check the availability and responsiveness of a server
    by performing a lightweight connection test to a specific endpoint. It encapsulates
    the logic to send a request and interpret its response, ensuring it is simple to
    evaluate the connection status programmatically.
    """

    def __init__(self, base_url: str, client: httpx_client.OpikHttpxClient):
        """
        Initializes an instance with a base URL and an HTTP client.

        This constructor sets up the necessary URLs and initializes the
        client needed to make requests.

        Args:
            base_url: The base URL for API requests.
            client: The HTTP client instance to make API calls.
        """
        self._ping_url = url_helpers.get_is_alive_ping_url(base_url)
        self._client = client

    def check_connection(self, timeout: float) -> ProbeResult:
        """
        Checks the connection to a specified server by sending a ping request and evaluates the response.

        This method performs a basic health check by sending an HTTP GET request to the server's ping URL.
        If the response status code is 200, it indicates the server is healthy, otherwise it is considered unhealthy.
        Handles timeouts and other unexpected exceptions during the connection attempt.

        Args:
            timeout: The maximum duration, in seconds, to wait for the server's response before timing out.

        Returns:
            An object indicating whether the server is healthy and, if not, the associated error message.
        """
        try:
            response = self._client.get(self._ping_url, timeout=timeout)
            if response.status_code == 200:
                return ProbeResult(is_healthy=True, error_message=None)
            else:
                return ProbeResult(
                    is_healthy=False,
                    error_message=f"Unexpected status code: {response.status_code}",
                )
        except (httpx.ConnectError, httpx.TimeoutException) as e:
            return ProbeResult(is_healthy=False, error_message=f"Connection error: {e}")
        except Exception as e:
            LOGGER.exception(
                "Unexpected error while checking connection to server: %s",
                e,
                exc_info=True,
            )
            return ProbeResult(is_healthy=False, error_message=f"Unexpected error: {e}")
