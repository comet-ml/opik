import gzip
import json
from unittest import mock

from opik import httpx_client
import opik.hooks
from opik.httpx_client import (
    CONNECT_TIMEOUT_SECONDS,
    READ_TIMEOUT_SECONDS,
    WRITE_TIMEOUT_SECONDS,
    POOL_TIMEOUT_SECONDS,
)


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
