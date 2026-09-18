"""Capture and decode the request bodies a Dataset uploads.

Dataset items are serialised and compressed by the SDK itself, so a test that wants to
know what was inserted has to look at the request body rather than at the arguments handed
to the generated REST client. Decoding here keeps the assertions readable -- a test can
still say `capture.items[0]["data"]` -- while checking what actually goes on the wire.
"""

import gzip
import inspect
import json
from typing import Any, Awaitable, Callable, Dict, List, Optional

import httpx


class UploadCapture:
    """Stands in for the SDK's httpx client and records every prepared body.

    A real client over `httpx.MockTransport` rather than a stub with a `request` method:
    the upload sends over an async twin of the client it is given, and only a transport
    that serves both paths sees what either of them sent.
    """

    base_url = "http://testserver/api/"

    def __init__(
        self,
        status_code: int = 204,
        responses: Optional[List[int]] = None,
        response_headers: Optional[Dict[str, str]] = None,
        on_request: Optional[Callable[[], Optional[Awaitable[None]]]] = None,
    ) -> None:
        self.bodies: List[bytes] = []
        self.urls: List[str] = []
        self.request_headers: List[httpx.Headers] = []
        self._status_code = status_code
        # Consumed in order when given, so a test can script a 429 followed by a success.
        self._responses = list(responses) if responses is not None else None
        self._response_headers = response_headers or {}
        # Runs inside the request, so a test can observe or hold uploads in flight.
        self._on_request = on_request
        self.client = httpx.Client(
            transport=httpx.MockTransport(self.handle), base_url=self.base_url
        )

    def handle(self, request: httpx.Request) -> Any:
        held = self._on_request() if self._on_request is not None else None
        if inspect.isawaitable(held):
            return self._held(request, held)
        return self._respond(request)

    async def _held(
        self, request: httpx.Request, held: Awaitable[None]
    ) -> httpx.Response:
        # A hook that awaits holds its own upload without stalling the loop the other
        # uploads share, which is the only way concurrent sends can be observed at all.
        await held
        return self._respond(request)

    def _respond(self, request: httpx.Request) -> httpx.Response:
        self.urls.append(str(request.url))
        self.bodies.append(request.content)
        self.request_headers.append(request.headers)

        status = self._status_code
        if self._responses:
            status = self._responses.pop(0)

        return httpx.Response(status, headers=self._response_headers, content=b"{}")

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


def rest_client_with_transport(capture: "UploadCapture", **attrs: Any) -> Any:
    """A mock REST client whose own httpx transport is `capture`.

    How a `Dataset` built from nothing but a REST client resolves its transport: the
    generated client's wrapper holds the real `OpikHttpxClient`, so the upload reaches it
    without the caller supplying anything.
    """
    from unittest.mock import Mock

    rest_client = Mock(**attrs)
    rest_client._client_wrapper.httpx_client.httpx_client = capture.client
    rest_client._client_wrapper.get_base_url.return_value = capture.base_url
    return rest_client


def make_dataset(
    dataset_cls: Any, rest_client: Any, capture: UploadCapture, **kwargs: Any
):
    """Build a Dataset wired to a capture instead of a real HTTP client."""
    params = {
        "name": "test_dataset",
        "description": "Test description",
        "project_name": "Test project",
        "rest_client": rest_client,
        "rest_httpx_client": capture.client,
        "url_override": capture.base_url,
    }
    params.update(kwargs)
    return dataset_cls(**params)
