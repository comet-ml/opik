import gzip
import json
from unittest import mock

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

    json_data = {"a": 1}
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

        assert len(capture_log.records) == 1
        assert capture_log.records[0].levelname == "WARNING"
        assert (
            capture_log.records[0].message
            == f"Deprecation warning for GET {rx_url}: deprecated"
        )

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

        assert capture_log.records[0].levelname == "WARNING"
        assert (
            capture_log.records[0].message
            == f"Deprecation warning for GET {rx_url}: deprecated"
        )

        assert response.status_code == 200
