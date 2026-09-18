import asyncio
import gzip
import json
from unittest import mock

import httpx
import pytest
import respx

from opik import httpx_client
from opik.httpx_client import (
    CONNECT_TIMEOUT_SECONDS,
    DEPRECATION_HEADER,
    POOL_TIMEOUT_SECONDS,
    READ_TIMEOUT_SECONDS,
    WRITE_TIMEOUT_SECONDS,
)
import opik.hooks


def test_json_compression__compressed_if_json(respx_mock):
    rx_url = "https://example.com"
    respx_mock.post(rx_url).respond(200)

    client = httpx_client.get(
        None, None, check_tls_certificate=False, compress_json_requests=True
    )

    # Past the entity-size floor, below which the body is sent as it is -- the same
    # rule the backend applies to responses. The floor itself is covered in
    # test_request_compression.py.
    json_data = {"a": "padding" * 64}
    client.post(rx_url, json=json_data)

    assert len(respx_mock.calls) == 1
    request = respx_mock.calls[0].request
    content = gzip.decompress(request.read())

    assert json.loads(content) == json_data


def test_json_compression__uncompressed_if_not_json(respx_mock):
    rx_url = "https://example.com"
    respx_mock.post(rx_url).respond(200)

    client = httpx_client.get(
        None, None, check_tls_certificate=False, compress_json_requests=True
    )

    txt_data = b"this is not json"
    client.post(rx_url, content=txt_data)

    assert len(respx_mock.calls) == 1
    content = respx_mock.calls[0].request.read()

    assert content == txt_data


def test_httpx_client_hooks__callable_hook_applied():
    mock_callable = mock.MagicMock()
    hook = opik.hooks.HttpxClientHook(
        client_modifier=mock_callable, client_init_arguments=None
    )
    opik.hooks.add_httpx_client_hook(hook)

    client = httpx_client.get(
        None, None, check_tls_certificate=False, compress_json_requests=True
    )

    mock_callable.assert_called_once_with(client)


def test_httpx_client_hooks__callable_hook_applied_with_arguments():
    mock_callable = mock.MagicMock()
    hook = opik.hooks.HttpxClientHook(
        client_modifier=mock_callable, client_init_arguments={"trust_env": False}
    )
    opik.hooks.add_httpx_client_hook(hook)

    client = httpx_client.get(
        None, None, check_tls_certificate=False, compress_json_requests=True
    )

    mock_callable.assert_called_once_with(client)

    # check that the default arguments are set
    assert client.timeout.connect == CONNECT_TIMEOUT_SECONDS
    assert client.timeout.read == READ_TIMEOUT_SECONDS
    assert client.timeout.write == WRITE_TIMEOUT_SECONDS
    assert client.timeout.pool == POOL_TIMEOUT_SECONDS
    assert client.follow_redirects is True

    # check custom arguments
    assert client.trust_env is False


def test_httpx_client_hooks__callable_hook_applied__with_arguments_hook_applied_afterwards():
    # apply a first hook with callable
    mock_callable = mock.MagicMock()
    hook = opik.hooks.HttpxClientHook(
        client_modifier=mock_callable, client_init_arguments=None
    )
    opik.hooks.add_httpx_client_hook(hook)

    # apply a second hook with custom arguments
    hook2 = opik.hooks.HttpxClientHook(
        client_modifier=None, client_init_arguments={"trust_env": False}
    )
    opik.hooks.add_httpx_client_hook(hook2)

    client = httpx_client.get(
        None, None, check_tls_certificate=False, compress_json_requests=True
    )

    mock_callable.assert_called_once_with(client)

    # check custom arguments
    assert client.trust_env is False


def test_get_httpx_client__no_hooks():
    client = httpx_client.get(
        None, None, check_tls_certificate=False, compress_json_requests=True
    )
    assert client is not None


@pytest.fixture
def isolated_hooks():
    """Hooks are global and nothing else restores them; a leaked one would reach every
    later test's client."""
    registered = opik.hooks.httpx_client_hook._httpx_client_hooks
    opik.hooks.httpx_client_hook._httpx_client_hooks = []
    try:
        yield
    finally:
        opik.hooks.httpx_client_hook._httpx_client_hooks = registered


def test_async_twin__hook_supplying_a_sync_transport__refuses_rather_than_bypassing(
    isolated_hooks,
):
    """A transport is often the only thing enforcing a proxy, an allow-list or mTLS.

    `{"transport": httpx.HTTPTransport(retries=5)}` is a documented httpx recipe and
    cannot be used by an async client. Falling back to httpx's default async transport
    would upload *successfully* while sending around whatever the hook was enforcing,
    which is the worst of the three outcomes; the caller answers this by uploading over
    the sync client instead.
    """
    opik.hooks.add_httpx_client_hook(
        opik.hooks.HttpxClientHook(
            client_modifier=None,
            client_init_arguments={"transport": httpx.HTTPTransport(retries=5)},
        )
    )
    client = httpx_client.get(
        None, None, check_tls_certificate=False, compress_json_requests=True
    )

    with pytest.raises(httpx_client.AsyncTransportUnavailable):
        httpx_client.async_twin(client, max_connections=8)


def test_async_twin__hook_supplying_a_sync_mount__refuses_rather_than_bypassing(
    isolated_hooks,
):
    """A mount is per-host policy, so dropping one bypasses it for exactly that host."""
    opik.hooks.add_httpx_client_hook(
        opik.hooks.HttpxClientHook(
            client_modifier=None,
            client_init_arguments={"mounts": {"https://vault": httpx.HTTPTransport()}},
        )
    )
    client = httpx_client.get(
        None, None, check_tls_certificate=False, compress_json_requests=True
    )

    with pytest.raises(httpx_client.AsyncTransportUnavailable, match="https://vault"):
        httpx_client.async_twin(client, max_connections=8)


def test_async_twin__hook_supplying_an_async_transport__borrows_it(isolated_hooks):
    """Lent, not given. A hook holds one dict of arguments, so this is the same object
    the caller's long-lived sync client sends through -- closing the upload's client must
    not close it, or every later request in the process sends through a dead pool."""

    class Tracked(httpx.AsyncHTTPTransport):
        def __init__(self) -> None:
            super().__init__()
            self.closed = False

        async def aclose(self) -> None:
            self.closed = True
            await super().aclose()

    shared = Tracked()
    opik.hooks.add_httpx_client_hook(
        opik.hooks.HttpxClientHook(
            client_modifier=None, client_init_arguments={"transport": shared}
        )
    )
    client = httpx_client.get(
        None, None, check_tls_certificate=False, compress_json_requests=True
    )
    twin = httpx_client.async_twin(client, max_connections=8)

    asyncio.run(twin.aclose())

    assert not shared.closed, (
        "The upload's client closed a transport it only borrowed from the caller"
    )


def test_usable_async_settings__null_event_hooks__normalised_not_iterated():
    """`{"request": None}` is a plausible thing for a hook to write.

    Asserted against the filter directly rather than through `async_twin`, because
    `httpx.Client` rejects it first -- such a hook breaks every Opik client, not just the
    upload, so this is the belt rather than the braces. It stays because the filter is
    where a hook's arguments are trusted, and iterating `None` there would fail an upload
    that had otherwise been configured correctly.
    """
    usable = httpx_client._usable_async_settings(
        {"event_hooks": {"request": None, "response": []}}
    )

    assert usable["event_hooks"] == {"request": [], "response": []}


def test_async_twin__hook_supplying_headers__keeps_the_identity(isolated_hooks):
    """Hook arguments are merged *over* the twin's own, so a hook registered with
    `{"headers": ...}` would replace the copied set and drop Authorization and
    Comet-Workspace: uploads 403 while every other request in the process works."""
    opik.hooks.add_httpx_client_hook(
        opik.hooks.HttpxClientHook(
            client_modifier=None,
            client_init_arguments={"headers": {"X-Tenant": "acme"}},
        )
    )

    client = httpx_client.get(
        "the-workspace",
        "the-api-key",
        check_tls_certificate=False,
        compress_json_requests=True,
    )
    twin = httpx_client.async_twin(client, max_connections=8)

    assert twin.headers.get("authorization") == "the-api-key"
    assert twin.headers.get("comet-workspace") == "the-workspace"
    # The hook's own header still rides along; it is already on the sync client.
    assert twin.headers.get("x-tenant") == "acme"


def test_async_twin__verify_comes_from_the_sync_client(isolated_hooks):
    """Recorded by `get()` rather than reverse-engineered: an install that turned
    verification off must not have it turned back on for dataset uploads alone."""
    client = httpx_client.get(
        None, None, check_tls_certificate=False, compress_json_requests=True
    )

    assert client.verify_setting is False


class TestOpikHttpxClientDeprecationHeader:
    """Tests for X-Opik-Deprecation response header handling."""

    @pytest.fixture(autouse=True)
    def setup(self):
        self.client = httpx_client.get(
            None, None, check_tls_certificate=False, compress_json_requests=False
        )

        yield

        self.client.close()

    @respx.mock
    def test_deprecation_header_present__logged_only_once_for_same_path(
        self, capture_log
    ):
        """Verify the same deprecation warning is not repeated across multiple calls to the same path."""
        rx_url = "https://foo.bar/api/v1/deprecated"
        respx.get(rx_url).respond(200, headers={DEPRECATION_HEADER: "deprecated"})

        self.client.get(rx_url)
        self.client.get(rx_url)

        records = [r for r in capture_log.records if rx_url in r.message]
        assert len(records) == 1
        assert records[0].levelname == "WARNING"
        assert records[0].message == f"Deprecation warning for GET {rx_url}: deprecated"

    @respx.mock
    def test_deprecation_header_absent__no_warning_logged_and_response_still_returned(
        self,
    ):
        """Verify no warning is logged when the response does not contain X-Opik-Deprecation and the response is returned normally."""
        rx_url = "https://foo.bar/api/v1/normal"
        respx.get(rx_url).respond(200)

        with mock.patch("opik.httpx_client.LOGGER") as mock_logger:
            response = self.client.get(rx_url)

        mock_logger.warning.assert_not_called()
        assert response.status_code == 200

    @respx.mock
    def test_deprecation_header_present__warning_logged_and_response_still_returned(
        self, capture_log
    ):
        """Verify the response is returned normally even when the deprecation header is present."""
        rx_url = "https://localhost/api/v2/deprecated"
        respx.get(rx_url).respond(200, headers={DEPRECATION_HEADER: "deprecated"})

        response = self.client.get(rx_url)

        records = [r for r in capture_log.records if rx_url in r.message]
        assert records[0].levelname == "WARNING"
        assert records[0].message == f"Deprecation warning for GET {rx_url}: deprecated"

        assert response.status_code == 200
