from unittest.mock import MagicMock, Mock, patch

import httpx
import pytest

from opik.configurator.configure import (
    OPIK_BASE_URL_CLOUD,
)
from opik.configurator import opik_rest_helpers


class TestIsInstanceActive:
    @pytest.mark.parametrize(
        "status_code, expected_result",
        [
            (200, True),
            (404, False),
            (500, False),
        ],
    )
    @patch("opik.configurator.opik_rest_helpers.httpx_client.get")
    def test_is_instance_active(self, mock_httpx_client, status_code, expected_result):
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
        result = opik_rest_helpers.is_instance_active(url)

        assert result == expected_result

    @patch("opik.configurator.opik_rest_helpers.httpx_client.get")
    def test_is_instance_active_timeout(self, mock_httpx_client):
        """
        Test that a connection timeout results in False being returned.
        """
        mock_client_instance = MagicMock()
        mock_client_instance.__enter__.return_value = mock_client_instance
        mock_client_instance.__exit__.return_value = False
        mock_client_instance.get.side_effect = httpx.ConnectTimeout("timeout")

        mock_httpx_client.return_value = mock_client_instance

        url = "http://example.com"
        result = opik_rest_helpers.is_instance_active(url)

        assert result is False

    @patch("opik.configurator.opik_rest_helpers.httpx_client.get")
    def test_is_instance_active_general_exception(self, mock_httpx_client):
        """
        Test that any general exception results in False being returned.
        """
        mock_client_instance = MagicMock()
        mock_client_instance.__enter__.return_value = mock_client_instance
        mock_client_instance.__exit__.return_value = False
        mock_client_instance.get.side_effect = Exception("Unexpected error")

        mock_httpx_client.return_value = mock_client_instance

        url = "http://example.com"
        result = opik_rest_helpers.is_instance_active(url)

        assert result is False


class TestIsWorkspaceNameCorrect:
    @pytest.mark.parametrize(
        "api_key, workspace, workspace_names, expected_result",
        [
            ("valid_api_key", "correct_workspace", ["correct_workspace"], True),
            ("valid_api_key", "incorrect_workspace", ["other_workspace"], False),
            ("valid_api_key", "empty_workspace", [], False),
        ],
    )
    @patch("opik.configurator.opik_rest_helpers.httpx_client.get")
    def test_workspace_valid_api_key(
        self, mock_httpx_client, api_key, workspace, workspace_names, expected_result
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

        result = opik_rest_helpers.is_workspace_name_correct(
            api_key=api_key, workspace=workspace, url=OPIK_BASE_URL_CLOUD
        )
        assert result == expected_result

    @pytest.mark.parametrize(
        "status_code, response_text",
        [(500, "Internal Server Error"), (404, "Not Found"), (403, "Forbidden")],
    )
    @patch("opik.configurator.opik_rest_helpers.httpx_client.get")
    def test_workspace_non_200_response(
        self, mock_httpx_client, status_code, response_text
    ):
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
            opik_rest_helpers.is_workspace_name_correct(
                api_key=api_key, workspace=workspace, url=OPIK_BASE_URL_CLOUD
            )

    @pytest.mark.parametrize(
        "exception",
        [
            (httpx.RequestError("Timeout", request=MagicMock())),
            (Exception("Unexpected error")),
        ],
    )
    @patch("opik.configurator.opik_rest_helpers.httpx_client.get")
    def test_workspace_request_exceptions(self, mock_httpx_client, exception):
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
            opik_rest_helpers.is_workspace_name_correct(
                api_key=api_key, workspace=workspace, url=OPIK_BASE_URL_CLOUD
            )


class TestIsApiKeyCorrect:
    @pytest.mark.parametrize(
        "status_code, expected_result",
        [
            (200, True),
            (401, False),
            (403, False),
        ],
    )
    @patch("opik.configurator.opik_rest_helpers.httpx_client.get")
    def test_is_api_key_correct(self, mock_httpx_client, status_code, expected_result):
        """
        Test valid, invalid, and forbidden API key scenarios by simulating HTTP status codes.
        """
        mock_client_instance = MagicMock()
        mock_response = Mock()
        mock_response.status_code = status_code

        mock_client_instance.__enter__.return_value = mock_client_instance
        mock_client_instance.__exit__.return_value = False
        mock_client_instance.get.return_value = mock_response
        mock_httpx_client.return_value = mock_client_instance

        api_key = "dummy_api_key"
        result = opik_rest_helpers.is_api_key_correct(
            api_key, url="https://some-url.com"
        )

        assert result == expected_result

    @pytest.mark.parametrize(
        "status_code, response_text",
        [(500, "Internal Server Error")],
    )
    @patch("opik.configurator.opik_rest_helpers.httpx_client.get")
    def test_is_api_key_correct_non_200_response(
        self, mock_httpx_client, status_code, response_text
    ):
        """
        Test that a non-200, 401, or 403 response raises a ConnectionError.
        """
        mock_client_instance = MagicMock()
        mock_response = Mock()
        mock_response.status_code = status_code
        mock_response.text = response_text

        mock_client_instance.__enter__.return_value = mock_client_instance
        mock_client_instance.__exit__.return_value = False
        mock_client_instance.get.return_value = mock_response
        mock_httpx_client.return_value = mock_client_instance

        api_key = "dummy_api_key"

        with pytest.raises(ConnectionError):
            opik_rest_helpers.is_api_key_correct(api_key, url="https://some-url.com")

    @pytest.mark.parametrize(
        "exception",
        [
            (httpx.RequestError("Timeout", request=MagicMock())),
            (Exception("Unexpected error")),
        ],
    )
    @patch("opik.configurator.opik_rest_helpers.httpx_client.get")
    def test_is_api_key_correct_exceptions(self, mock_httpx_client, exception):
        """
        Test that RequestError and general exceptions are properly raised as ConnectionError.
        """
        mock_client_instance = MagicMock()
        mock_client_instance.__enter__.return_value = mock_client_instance
        mock_client_instance.__exit__.return_value = False
        mock_client_instance.get.side_effect = exception

        mock_httpx_client.return_value = mock_client_instance

        api_key = "dummy_api_key"

        with pytest.raises(ConnectionError):
            opik_rest_helpers.is_api_key_correct(api_key, url="https://some-url.com")
