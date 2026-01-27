"""
Minimal ColBERTv2 implementation extracted from dspy (MIT license).

This module provides a lightweight implementation of ColBERTv2 search functionality
without requiring the full dspy dependency.
"""

import copy
import time
from typing import Any
import requests  # type: ignore[import-untyped]
from requests.adapters import HTTPAdapter  # type: ignore[import-untyped]
from urllib3.util.retry import Retry


def _create_session_with_retries(max_retries: int = 4) -> requests.Session:
    """
    Create a requests session with retry configuration.

    Args:
        max_retries: Maximum number of retry attempts

    Returns:
        Configured requests session
    """
    session = requests.Session()

    retry_strategy = Retry(
        total=max_retries,
        backoff_factor=1,  # Wait 1, 2, 4, 8 seconds between retries
        status_forcelist=[429, 500, 502, 503, 504],  # HTTP status codes to retry on
        allowed_methods=["HEAD", "GET", "POST", "PUT", "DELETE", "OPTIONS", "TRACE"],
    )

    adapter = HTTPAdapter(max_retries=retry_strategy)
    session.mount("http://", adapter)
    session.mount("https://", adapter)

    return session


class dotdict(dict):
    """Dictionary with attribute access (extracted from dspy)."""

    def __getattr__(self, key: str) -> Any:
        if key.startswith("__") and key.endswith("__"):
            return super().__getattribute__(key)
        try:
            return self[key]
        except KeyError:
            raise AttributeError(
                f"'{type(self).__name__}' object has no attribute '{key}'"
            )

    def __setattr__(self, key: str, value: Any) -> None:
        if key.startswith("__") and key.endswith("__"):
            super().__setattr__(key, value)
        else:
            self[key] = value

    def __delattr__(self, key: str) -> None:
        if key.startswith("__") and key.endswith("__"):
            super().__delattr__(key)
        else:
            del self[key]

    def __deepcopy__(self, memo: dict[Any, Any]) -> "dotdict":
        # Use the default dict copying method to avoid infinite recursion.
        return dotdict(copy.deepcopy(dict(self), memo))


def colbertv2_get_request(
    url: str, query: str, k: int, max_retries: int = 4
) -> list[dict[str, Any]]:
    """
    Make a GET request to ColBERTv2 server with retry logic.

    Args:
        url: The ColBERTv2 server URL
        query: The search query
        k: Number of results to return
        max_retries: Maximum number of retry attempts

    Returns:
        List of search results
    """
    assert k <= 100, (
        "Only k <= 100 is supported for the hosted ColBERTv2 server at the moment."
    )

    session = _create_session_with_retries(max_retries)
    payload: dict[str, str | int] = {"query": query, "k": k}

    # Application-level retry for server connection errors
    for attempt in range(max_retries):
        try:
            res = session.get(url, params=payload, timeout=5)
            response_data = res.json()

            # Check for application-level errors (server connection issues, etc.)
            if "error" in response_data and response_data["error"]:
                error_msg = response_data.get("message", "Unknown error")
                # If it's a connection error, retry; otherwise, fail immediately
                if (
                    "Cannot connect to host" in error_msg
                    or "Connection refused" in error_msg
                ):
                    if attempt == max_retries - 1:
                        raise Exception(f"ColBERTv2 server error: {error_msg}")
                    time.sleep(1)  # Wait 1 second before retrying
                    continue
                else:
                    raise Exception(f"ColBERTv2 server error: {error_msg}")

            if "topk" not in response_data:
                raise Exception(
                    f"Unexpected response format from ColBERTv2 server: {list(response_data.keys())}"
                )

            topk = response_data["topk"][:k]
            topk = [{**d, "long_text": d["text"]} for d in topk]
            return topk[:k]

        except requests.RequestException as e:
            if attempt == max_retries - 1:
                raise Exception(f"ColBERTv2 request failed: {str(e)}")
            time.sleep(1)  # Wait 1 second before retrying

    # This should never be reached, but mypy requires a return statement
    raise Exception("Unexpected end of retry loop")


def colbertv2_post_request(
    url: str, query: str, k: int, max_retries: int = 4
) -> list[dict[str, Any]]:
    """
    Make a POST request to ColBERTv2 server with retry logic.

    Args:
        url: The ColBERTv2 server URL
        query: The search query
        k: Number of results to return
        max_retries: Maximum number of retry attempts

    Returns:
        List of search results
    """
    session = _create_session_with_retries(max_retries)
    headers = {"Content-Type": "application/json; charset=utf-8"}
    payload = {"query": query, "k": k}

    # Application-level retry for server connection errors
    for attempt in range(max_retries):
        try:
            res = session.post(url, json=payload, headers=headers, timeout=5)
            response_data = res.json()

            # Check for application-level errors (server connection issues, etc.)
            if "error" in response_data and response_data["error"]:
                error_msg = response_data.get("message", "Unknown error")
                # If it's a connection error, retry; otherwise, fail immediately
                if (
                    "Cannot connect to host" in error_msg
                    or "Connection refused" in error_msg
                ):
                    if attempt == max_retries - 1:
                        raise Exception(f"ColBERTv2 server error: {error_msg}")
                    time.sleep(1)  # Wait 1 second before retrying
                    continue
                else:
                    raise Exception(f"ColBERTv2 server error: {error_msg}")

            if "topk" not in response_data:
                raise Exception(
                    f"Unexpected response format from ColBERTv2 server: {list(response_data.keys())}"
                )

            return response_data["topk"][:k]

        except requests.RequestException as e:
            if attempt == max_retries - 1:
                raise Exception(f"ColBERTv2 request failed: {str(e)}")
            time.sleep(1)  # Wait 1 second before retrying

    # This should never be reached, but mypy requires a return statement
    raise Exception("Unexpected end of retry loop")


class ColBERTv2:
    """Wrapper for the ColBERTv2 Retrieval (extracted from dspy)."""

    def __init__(
        self,
        url: str = "http://0.0.0.0",
        port: str | int | None = None,
        post_requests: bool = False,
    ):
        """
        Initialize ColBERTv2 client.

        Args:
            url: Base URL for the ColBERTv2 server
            port: Optional port number
            post_requests: Whether to use POST requests instead of GET
        """
        self.post_requests = post_requests
        self.url = f"{url}:{port}" if port else url

    def __call__(
        self,
        query: str,
        k: int = 10,
        simplify: bool = False,
        max_retries: int = 4,
    ) -> list[str] | list[dotdict]:
        """
        Search using ColBERTv2.

        Args:
            query: The search query
            k: Number of results to return
            simplify: If True, return only text strings; if False, return dotdict objects
            max_retries: Maximum number of retry attempts

        Returns:
            List of search results (either strings or dotdict objects)
        """
        if self.post_requests:
            topk_results = colbertv2_post_request(self.url, query, k, max_retries)
        else:
            topk_results = colbertv2_get_request(self.url, query, k, max_retries)

        if simplify:
            return [psg["long_text"] for psg in topk_results]

        return [dotdict(psg) for psg in topk_results]
