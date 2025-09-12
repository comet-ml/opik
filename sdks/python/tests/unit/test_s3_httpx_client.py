from unittest import mock

import opik.hooks
from opik import s3_httpx_client
from opik.s3_httpx_client import (
    CONNECT_TIMEOUT_SECONDS,
    READ_TIMEOUT_SECONDS,
    WRITE_TIMEOUT_SECONDS,
    POOL_TIMEOUT_SECONDS,
)


def test_httpx_client_hooks__callable_hook_applied():
    mock_callable = mock.MagicMock()
    hook = opik.hooks.HttpxClientHook(
        client_modifier=mock_callable, client_init_arguments=None
    )
    opik.hooks.add_httpx_client_hook(hook)

    client = s3_httpx_client.get()

    mock_callable.assert_called_once_with(client)


def test_httpx_client_hooks__callable_hook_applied_with_arguments():
    mock_callable = mock.MagicMock()
    hook = opik.hooks.HttpxClientHook(
        client_modifier=mock_callable, client_init_arguments={"trust_env": False}
    )
    opik.hooks.add_httpx_client_hook(hook)

    client = s3_httpx_client.get()

    mock_callable.assert_called_once_with(client)

    # check that the default arguments are set
    assert client.timeout.connect == CONNECT_TIMEOUT_SECONDS
    assert client.timeout.read == READ_TIMEOUT_SECONDS
    assert client.timeout.write == WRITE_TIMEOUT_SECONDS
    assert client.timeout.pool == POOL_TIMEOUT_SECONDS

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

    client = s3_httpx_client.get()

    mock_callable.assert_called_once_with(client)

    # check custom arguments
    assert client.trust_env is False


def test_get_httpx_client__no_hooks():
    client = s3_httpx_client.get()
    assert client is not None
