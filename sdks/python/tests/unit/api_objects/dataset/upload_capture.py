"""Capture and decode the request bodies a Dataset uploads.

Dataset items are serialised and compressed by the SDK itself, so a test that wants to
know what was inserted has to look at the request body rather than at the arguments handed
to the generated REST client. Decoding here keeps the assertions readable -- a test can
still say `capture.items[0]["data"]` -- while checking what actually goes on the wire.
"""

import gzip
import json
from typing import Any, Dict, List, Optional


class _Response:
    def __init__(self, status_code: int, body: str = "") -> None:
        self.status_code = status_code
        self.headers: Dict[str, str] = {}
        self.text = body


class UploadCapture:
    """Stands in for the SDK's httpx client and records every prepared body."""

    base_url = "http://testserver/api/"

    def __init__(
        self,
        status_code: int = 204,
        responses: Optional[List[int]] = None,
        response_headers: Optional[Dict[str, str]] = None,
    ) -> None:
        self.bodies: List[bytes] = []
        self.urls: List[str] = []
        self._status_code = status_code
        # Consumed in order when given, so a test can script a 429 followed by a success.
        self._responses = list(responses) if responses is not None else None
        self._response_headers = response_headers or {}

    def request(self, method: str, url: str, **kwargs: Any) -> _Response:
        self.urls.append(url)
        self.bodies.append(kwargs["content"])

        status = self._status_code
        if self._responses:
            status = self._responses.pop(0)

        response = _Response(status, body="{}")
        response.headers = dict(self._response_headers)
        return response

    @property
    def request_count(self) -> int:
        return len(self.bodies)

    @property
    def payloads(self) -> List[Dict[str, Any]]:
        """Each request body, gunzipped and parsed."""
        return [json.loads(gzip.decompress(body)) for body in self.bodies]

    @property
    def batches(self) -> List[List[Dict[str, Any]]]:
        """The item list from each request, one list per request."""
        return [payload["items"] for payload in self.payloads]

    @property
    def items(self) -> List[Dict[str, Any]]:
        """Every item sent, flattened across requests."""
        return [item for batch in self.batches for item in batch]


def make_dataset(
    dataset_cls: Any, rest_client: Any, capture: UploadCapture, **kwargs: Any
):
    """Build a Dataset wired to a capture instead of a real HTTP client."""
    params = {
        "name": "test_dataset",
        "description": "Test description",
        "project_name": "Test project",
        "rest_client": rest_client,
        "rest_httpx_client": capture,
        "url_override": capture.base_url,
    }
    params.update(kwargs)
    return dataset_cls(**params)
