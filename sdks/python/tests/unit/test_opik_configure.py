from unittest.mock import MagicMock, Mock, patch

import httpx
import pytest

from opik.exceptions import ConfigurationError
from opik.opik_configure import configure, is_instance_active, is_workspace_name_correct


@pytest.mark.parametrize(
    "status_code, expected_result",
    [
        (200, True),
        (404, False),
        (500, False),
    ],
)
@patch("opik.opik_configure.httpx.Client")
def test_is_instance_active(mock_httpx_client, status_code, expected_result):
    """
    Test various HTTP status code responses to check if the instance is active.
    """
    mock_client_instance = MagicMock()
    mock_response = Mock()
    mock_response.status_code = status_code

    mock_client_instance.__enter__.return_value = mock_client_instance
    mock_client_instance.__exit__.return_value = False
    mock_client_instance.get.return_value = mock_response
    mock_httpx_client.return_value = mock_client_instance

    url = "http://example.com"
    result = is_instance_active(url)

    assert result == expected_result


@patch("opik.opik_configure.httpx.Client")
def test_is_instance_active_timeout(mock_httpx_client):
    """
    Test that a connection timeout results in False being returned.
    """
    mock_client_instance = MagicMock()
    mock_client_instance.__enter__.return_value = mock_client_instance
    mock_client_instance.__exit__.return_value = False
    mock_client_instance.get.side_effect = httpx.ConnectTimeout("timeout")

    mock_httpx_client.return_value = mock_client_instance

    url = "http://example.com"
    result = is_instance_active(url)

    assert result is False


@patch("opik.opik_configure.httpx.Client")
def test_is_instance_active_general_exception(mock_httpx_client):
    """
    Test that any general exception results in False being returned.
    """
    mock_client_instance = MagicMock()
    mock_client_instance.__enter__.return_value = mock_client_instance
    mock_client_instance.__exit__.return_value = False
    mock_client_instance.get.side_effect = Exception("Unexpected error")

    mock_httpx_client.return_value = mock_client_instance

    url = "http://example.com"
    result = is_instance_active(url)

    assert result is False


@pytest.mark.parametrize(
    "api_key, workspace, workspace_names, expected_result",
    [
        ("valid_api_key", "correct_workspace", ["correct_workspace"], True),
        ("valid_api_key", "incorrect_workspace", ["other_workspace"], False),
        ("valid_api_key", "empty_workspace", [], False),
    ],
)
@patch("opik.opik_configure.httpx.Client")
def test_workspace_valid_api_key(
    mock_httpx_client, api_key, workspace, workspace_names, expected_result
):
    """
    Test cases with valid API keys and workspace verification.
    These tests simulate different workspace existence conditions.
    """
    # Mock the HTTP response for valid API key cases
    mock_client_instance = MagicMock()
    mock_response = Mock()
    mock_response.status_code = 200
    mock_response.json.return_value = {"workspaceNames": workspace_names}

    # Mock the context manager behavior
    mock_client_instance.__enter__.return_value = mock_client_instance
    mock_client_instance.__exit__.return_value = False
    mock_client_instance.get.return_value = mock_response
    mock_httpx_client.return_value = mock_client_instance

    result = is_workspace_name_correct(api_key, workspace)
    assert result == expected_result


@pytest.mark.parametrize(
    "status_code, response_text",
    [(500, "Internal Server Error"), (404, "Not Found"), (403, "Forbidden")],
)
@patch("opik.opik_configure.httpx.Client")
def test_workspace_non_200_response(mock_httpx_client, status_code, response_text):
    """
    Test cases where the API responds with a non-200 status code.
    These responses should raise a ConnectionError.
    """
    # Mock the HTTP response for non-200 status code cases
    mock_client_instance = MagicMock()
    mock_response = Mock()
    mock_response.status_code = status_code
    mock_response.text = response_text

    mock_client_instance.__enter__.return_value = mock_client_instance
    mock_client_instance.__exit__.return_value = False
    mock_client_instance.get.return_value = mock_response
    mock_httpx_client.return_value = mock_client_instance

    api_key = "valid_api_key"
    workspace = "any_workspace"

    with pytest.raises(ConnectionError):
        is_workspace_name_correct(api_key, workspace)


@pytest.mark.parametrize(
    "exception",
    [
        (httpx.RequestError("Timeout", request=MagicMock())),
        (Exception("Unexpected error")),
    ],
)
@patch("opik.opik_configure.httpx.Client")
def test_workspace_request_exceptions(mock_httpx_client, exception):
    """
    Test cases where an exception is raised during the HTTP request.
    These cases should raise a ConnectionError with the appropriate message.
    """
    # Mock the HTTP request to raise an exception
    mock_client_instance = MagicMock()
    mock_client_instance.__enter__.return_value = mock_client_instance
    mock_client_instance.__exit__.return_value = False
    mock_client_instance.get.side_effect = exception

    mock_httpx_client.return_value = mock_client_instance

    api_key = "valid_api_key"
    workspace = "any_workspace"

    # Check that the appropriate ConnectionError is raised
    with pytest.raises(ConnectionError):
        is_workspace_name_correct(api_key, workspace)


@pytest.mark.skip
@pytest.mark.parametrize(
    "api_key, url, workspace, local, should_raise",
    [
        (
            None,
            "http://example.com",
            "workspace1",
            True,
            False,
        ),  # Missing api_key, local=True
        (
            None,
            "http://example.com",
            "workspace1",
            False,
            True,
        ),  # Missing api_key, local=False
        ("apikey123", None, "workspace1", True, True),  # Missing url, local=True
        ("apikey123", None, "workspace1", False, True),  # Missing url, local=False
        (
            "apikey123",
            "http://example.com",
            None,
            True,
            True,
        ),  # Missing workspace, local=True
        (
            "apikey123",
            "http://example.com",
            None,
            False,
            True,
        ),  # Missing workspace, local=False
        (None, None, "workspace1", True, True),  # Missing api_key and url, local=True
        (None, None, "workspace1", False, True),  # Missing api_key and url, local=False
        (
            None,
            "http://example.com",
            None,
            True,
            True,
        ),  # Missing api_key and workspace, local=True
        (
            None,
            "http://example.com",
            None,
            False,
            True,
        ),  # Missing api_key and workspace, local=False
        ("apikey123", None, None, True, True),  # Missing url and workspace, local=True
        (
            "apikey123",
            None,
            None,
            False,
            True,
        ),  # Missing url and workspace, local=False
        (None, None, None, True, True),  # All missing, local=True
        (None, None, None, False, True),  # All missing, local=False
        (
            "apikey123",
            "http://example.com",
            "workspace1",
            True,
            False,
        ),  # All present, local=True
        (
            "apikey123",
            "http://example.com",
            "workspace1",
            False,
            False,
        ),  # All present, local=False
    ],
)
def test_login__force_new_settings__fail(api_key, url, workspace, local, should_raise):
    if should_raise:
        with pytest.raises(ConfigurationError):
            configure(
                api_key=api_key,
                url=url,
                workspace=workspace,
                force=True,
                use_local=local,
            )
    else:
        # No exception should be raised
        configure(
            api_key=api_key, url=url, workspace=workspace, force=True, use_local=local
        )
