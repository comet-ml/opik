from unittest.mock import MagicMock, Mock, patch

import httpx
import pytest

from opik.exceptions import ConfigurationError
from opik.opik_configure import (
    _update_config,
    get_default_workspace,
    is_api_key_correct,
    is_instance_active,
    is_workspace_name_correct,
)


class TestIsInstanceActive:
    @pytest.mark.parametrize(
        "status_code, expected_result",
        [
            (200, True),
            (404, False),
            (500, False),
        ],
    )
    @patch("opik.opik_configure.httpx.Client")
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
        result = is_instance_active(url)

        assert result == expected_result

    @patch("opik.opik_configure.httpx.Client")
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
        result = is_instance_active(url)

        assert result is False

    @patch("opik.opik_configure.httpx.Client")
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
        result = is_instance_active(url)

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
    @patch("opik.opik_configure.httpx.Client")
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

        result = is_workspace_name_correct(api_key, workspace)
        assert result == expected_result

    @pytest.mark.parametrize(
        "status_code, response_text",
        [(500, "Internal Server Error"), (404, "Not Found"), (403, "Forbidden")],
    )
    @patch("opik.opik_configure.httpx.Client")
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
            is_workspace_name_correct(api_key, workspace)

    @pytest.mark.parametrize(
        "exception",
        [
            (httpx.RequestError("Timeout", request=MagicMock())),
            (Exception("Unexpected error")),
        ],
    )
    @patch("opik.opik_configure.httpx.Client")
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
            is_workspace_name_correct(api_key, workspace)


class TestIsApiKeyCorrect:
    @pytest.mark.parametrize(
        "status_code, expected_result",
        [
            (200, True),
            (401, False),
            (403, False),
        ],
    )
    @patch("opik.opik_configure.httpx.Client")
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
        result = is_api_key_correct(api_key)

        assert result == expected_result

    @pytest.mark.parametrize(
        "status_code, response_text",
        [(500, "Internal Server Error")],
    )
    @patch("opik.opik_configure.httpx.Client")
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
            is_api_key_correct(api_key)

    @pytest.mark.parametrize(
        "exception",
        [
            (httpx.RequestError("Timeout", request=MagicMock())),
            (Exception("Unexpected error")),
        ],
    )
    @patch("opik.opik_configure.httpx.Client")
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
            is_api_key_correct(api_key)


class TestGetDefaultWorkspace:
    @pytest.mark.parametrize(
        "status_code, response_json, expected_result",
        [
            (200, {"defaultWorkspaceName": "workspace1"}, "workspace1"),
        ],
    )
    @patch("opik.opik_configure.httpx.Client")
    def test_get_default_workspace_success(
        self, mock_httpx_client, status_code, response_json, expected_result
    ):
        """
        Test successful retrieval of the default workspace name.
        """
        mock_client_instance = MagicMock()
        mock_response = Mock()
        mock_response.status_code = status_code
        mock_response.json.return_value = response_json

        mock_client_instance.__enter__.return_value = mock_client_instance
        mock_client_instance.get.return_value = mock_response
        mock_httpx_client.return_value = mock_client_instance

        api_key = "valid_api_key"
        result = get_default_workspace(api_key)
        assert result == expected_result

    @pytest.mark.parametrize(
        "status_code, response_text",
        [
            (500, "Internal Server Error"),
        ],
    )
    @patch("opik.opik_configure.httpx.Client")
    def test_get_default_workspace_non_200_status(
        self, mock_httpx_client, status_code, response_text
    ):
        """
        Test that non-200 status codes raise a ConnectionError.
        """
        mock_client_instance = MagicMock()
        mock_response = Mock()
        mock_response.status_code = status_code
        mock_response.text = response_text

        mock_client_instance.__enter__.return_value = mock_client_instance
        mock_client_instance.get.return_value = mock_response
        mock_httpx_client.return_value = mock_client_instance

        api_key = "valid_api_key"
        with pytest.raises(ConnectionError):
            get_default_workspace(api_key)

    @pytest.mark.parametrize(
        "response_json",
        [
            {},
            {"otherKey": "value"},
            None,
        ],
    )
    @patch("opik.opik_configure.httpx.Client")
    def test_get_default_workspace_missing_key(self, mock_httpx_client, response_json):
        """
        Test that missing 'defaultWorkspaceName' in the response raises a ConnectionError.
        """
        mock_client_instance = MagicMock()
        mock_response = Mock()
        mock_response.status_code = 200
        mock_response.json.return_value = response_json

        mock_client_instance.__enter__.return_value = mock_client_instance
        mock_client_instance.get.return_value = mock_response
        mock_httpx_client.return_value = mock_client_instance

        api_key = "valid_api_key"
        with pytest.raises(ConnectionError):
            get_default_workspace(api_key)

    @pytest.mark.parametrize(
        "exception",
        [
            httpx.RequestError("Timeout", request=MagicMock()),
            Exception("Unexpected error"),
        ],
    )
    @patch("opik.opik_configure.httpx.Client")
    def test_get_default_workspace_exceptions(self, mock_httpx_client, exception):
        """
        Test that network and unexpected exceptions are raised as ConnectionError.
        """
        mock_client_instance = MagicMock()
        mock_client_instance.__enter__.return_value = mock_client_instance
        mock_client_instance.get.side_effect = exception
        mock_httpx_client.return_value = mock_client_instance

        api_key = "valid_api_key"

        with pytest.raises(ConnectionError):
            get_default_workspace(api_key)


class TestUpdateConfig:
    @patch("opik.opik_configure.opik.config.OpikConfig")
    @patch("opik.opik_configure.opik.config.update_session_config")
    def test_update_config_success(self, mock_update_session_config, mock_opik_config):
        """
        Test successful update of the config and session.
        """
        mock_config_instance = MagicMock()
        mock_opik_config.return_value = mock_config_instance

        api_key = "dummy_api_key"
        url = "http://example.com"
        workspace = "workspace1"

        _update_config(api_key, url, workspace)

        # Ensure config object is created and saved
        mock_opik_config.assert_called_once_with(
            api_key=api_key,
            url_override=url,
            workspace=workspace,
        )
        mock_config_instance.save_to_file.assert_called_once()

        # Ensure session config is updated
        mock_update_session_config.assert_any_call("api_key", api_key)
        mock_update_session_config.assert_any_call("url_override", url)
        mock_update_session_config.assert_any_call("workspace", workspace)

    @patch("opik.opik_configure.opik.config.OpikConfig")
    @patch("opik.opik_configure.opik.config.update_session_config")
    def test_update_config_raises_exception(
        self, mock_update_session_config, mock_opik_config
    ):
        """
        Test that ConfigurationError is raised when an exception occurs.
        """
        mock_opik_config.side_effect = Exception("Unexpected error")

        api_key = "dummy_api_key"
        url = "http://example.com"
        workspace = "workspace1"

        with pytest.raises(ConfigurationError, match="Failed to update configuration."):
            _update_config(api_key, url, workspace)

        # Ensure save_to_file is not called due to the exception
        mock_update_session_config.assert_not_called()

    @patch("opik.opik_configure.opik.config.OpikConfig")
    @patch("opik.opik_configure.opik.config.update_session_config")
    def test_update_config_session_update_failure(
        self, mock_update_session_config, mock_opik_config
    ):
        """
        Test that ConfigurationError is raised if updating the session configuration fails.
        """
        mock_config_instance = MagicMock()
        mock_opik_config.return_value = mock_config_instance
        mock_update_session_config.side_effect = Exception("Session update failed")

        api_key = "dummy_api_key"
        url = "http://example.com"
        workspace = "workspace1"

        with pytest.raises(ConfigurationError, match="Failed to update configuration."):
            _update_config(api_key, url, workspace)

        # Ensure config object is created and saved
        mock_opik_config.assert_called_once_with(
            api_key=api_key,
            url_override=url,
            workspace=workspace,
        )
        mock_config_instance.save_to_file.assert_called_once()
