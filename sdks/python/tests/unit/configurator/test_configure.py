from pathlib import Path
from unittest.mock import MagicMock, Mock, patch

import httpx
import pytest

from opik.config import (
    OPIK_BASE_URL_CLOUD,
    OPIK_BASE_URL_LOCAL,
    OPIK_WORKSPACE_DEFAULT_NAME,
    OpikConfig,
)
from opik.configurator.configure import OpikConfigurator
from opik.exceptions import ConfigurationError


@pytest.fixture(autouse=True)
def mock_env_and_file(monkeypatch):
    monkeypatch.delenv("OPIK_API_KEY", raising=False)
    monkeypatch.delenv("OPIK_WORKSPACE", raising=False)
    monkeypatch.delenv("OPIK_URL_OVERRIDE", raising=False)

    with patch("builtins.open", side_effect=FileNotFoundError):
        yield


class TestIsInstanceActive:
    @pytest.mark.parametrize(
        "status_code, expected_result",
        [
            (200, True),
            (404, False),
            (500, False),
        ],
    )
    @patch("opik.configurator.configure.httpx.Client")
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
        result = OpikConfigurator()._is_instance_active(url)

        assert result == expected_result

    @patch("opik.configurator.configure.httpx.Client")
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
        result = OpikConfigurator()._is_instance_active(url)

        assert result is False

    @patch("opik.configurator.configure.httpx.Client")
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
        result = OpikConfigurator()._is_instance_active(url)

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
    @patch("opik.configurator.configure.httpx.Client")
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

        result = OpikConfigurator(
            api_key=api_key, workspace=workspace, url=OPIK_BASE_URL_CLOUD
        )._is_workspace_name_correct(workspace)
        assert result == expected_result

    @pytest.mark.parametrize(
        "status_code, response_text",
        [(500, "Internal Server Error"), (404, "Not Found"), (403, "Forbidden")],
    )
    @patch("opik.configurator.configure.httpx.Client")
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
            OpikConfigurator(
                api_key=api_key, workspace=workspace
            )._is_workspace_name_correct(workspace)

    @pytest.mark.parametrize(
        "exception",
        [
            (httpx.RequestError("Timeout", request=MagicMock())),
            (Exception("Unexpected error")),
        ],
    )
    @patch("opik.configurator.configure.httpx.Client")
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
            OpikConfigurator(
                api_key=api_key, workspace=workspace
            )._is_workspace_name_correct(workspace)


class TestIsApiKeyCorrect:
    @pytest.mark.parametrize(
        "status_code, expected_result",
        [
            (200, True),
            (401, False),
            (403, False),
        ],
    )
    @patch("opik.configurator.configure.httpx.Client")
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
        result = OpikConfigurator(url=OPIK_BASE_URL_CLOUD)._is_api_key_correct(api_key)

        assert result == expected_result

    @pytest.mark.parametrize(
        "status_code, response_text",
        [(500, "Internal Server Error")],
    )
    @patch("opik.configurator.configure.httpx.Client")
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
            OpikConfigurator()._is_api_key_correct(api_key)

    @pytest.mark.parametrize(
        "exception",
        [
            (httpx.RequestError("Timeout", request=MagicMock())),
            (Exception("Unexpected error")),
        ],
    )
    @patch("opik.configurator.configure.httpx.Client")
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
            OpikConfigurator()._is_api_key_correct(api_key)


class TestGetDefaultWorkspace:
    @pytest.mark.parametrize(
        "status_code, response_json, expected_result",
        [
            (200, {"defaultWorkspaceName": "workspace1"}, "workspace1"),
        ],
    )
    @patch("opik.configurator.configure.httpx.Client")
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
        result = OpikConfigurator(
            api_key=api_key, url=OPIK_BASE_URL_CLOUD
        )._get_default_workspace()
        assert result == expected_result

    @pytest.mark.parametrize(
        "status_code, response_text",
        [
            (500, "Internal Server Error"),
        ],
    )
    @patch("opik.configurator.configure.httpx.Client")
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
            OpikConfigurator(api_key=api_key)._get_default_workspace()

    @pytest.mark.parametrize(
        "response_json",
        [
            {},
            {"otherKey": "value"},
            None,
        ],
    )
    @patch("opik.configurator.configure.httpx.Client")
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
            OpikConfigurator(api_key=api_key)._get_default_workspace()

    @pytest.mark.parametrize(
        "exception",
        [
            httpx.RequestError("Timeout", request=MagicMock()),
            Exception("Unexpected error"),
        ],
    )
    @patch("opik.configurator.configure.httpx.Client")
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
            OpikConfigurator(api_key=api_key)._get_default_workspace()


class TestUpdateConfig:
    @patch("opik.configurator.configure.opik.config.OpikConfig")
    @patch("opik.configurator.configure.opik.config.update_session_config")
    def test_update_config_success(self, mock_update_session_config, mock_opik_config):
        """
        Test successful update of the config and session.
        """
        mock_config_instance = MagicMock()
        mock_opik_config.return_value = mock_config_instance

        api_key = "dummy_api_key"
        url = "http://example.com"
        workspace = "workspace1"

        OpikConfigurator(api_key, workspace, url)._update_config()

        # Ensure config object is created and saved
        mock_opik_config.assert_called_with(
            api_key=api_key,
            url_override=url,
            workspace=workspace,
        )
        mock_config_instance.save_to_file.assert_called_once()

        # Ensure session config is updated
        mock_update_session_config.assert_any_call("api_key", api_key)
        mock_update_session_config.assert_any_call("url_override", url)
        mock_update_session_config.assert_any_call("workspace", workspace)

    @patch("opik.configurator.configure.opik.config.OpikConfig")
    @patch("opik.configurator.configure.opik.config.update_session_config")
    def test_update_config_raises_exception(
        self, mock_update_session_config, mock_opik_config
    ):
        """
        Test that ConfigurationError is raised when an exception occurs during saving config file.
        """
        mock_opik_config.side_effect = [None, Exception("Unexpected error")]

        api_key = "dummy_api_key"
        url = "http://example.com"
        workspace = "workspace1"

        with pytest.raises(ConfigurationError, match="Failed to update configuration."):
            OpikConfigurator(api_key, workspace, url)._update_config()

        # Ensure save_to_file is not called due to the exception
        mock_update_session_config.assert_not_called()

    @patch("opik.configurator.configure.opik.config.OpikConfig")
    @patch("opik.configurator.configure.opik.config.update_session_config")
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
            OpikConfigurator(api_key, workspace, url)._update_config()

        # Ensure config object is created and saved
        mock_opik_config.assert_called_with(
            api_key=api_key,
            url_override=url,
            workspace=workspace,
        )
        mock_config_instance.save_to_file.assert_called_once()


class TestAskForUrl:
    @patch("builtins.input", side_effect=["http://valid-url.com"])
    @patch(
        "opik.configurator.configure.OpikConfigurator._is_instance_active",
        return_value=True,
    )
    def test_ask_for_url_success(self, mock_is_instance_active, mock_input):
        """
        Test successful input of a valid Opik URL.
        """
        config = OpikConfigurator()
        config._ask_for_url()
        assert config.url == "http://valid-url.com"
        mock_is_instance_active.assert_called_once_with("http://valid-url.com")

    @patch("builtins.input", side_effect=["http://invalid-url.com"] * 3)
    @patch(
        "opik.configurator.configure.OpikConfigurator._is_instance_active",
        return_value=False,
    )
    def test_ask_for_url_all_retries_fail(self, mock_is_instance_active, mock_input):
        """
        Test that after 3 failed attempts, a ConfigurationError is raised.
        """
        with pytest.raises(ConfigurationError, match="Cannot use the URL provided"):
            OpikConfigurator()._ask_for_url()

        assert mock_is_instance_active.call_count == 3

    @patch(
        "builtins.input", side_effect=["http://invalid-url.com", "http://valid-url.com"]
    )
    @patch(
        "opik.configurator.configure.OpikConfigurator._is_instance_active",
        side_effect=[False, True],
    )
    def test_ask_for_url_success_on_second_try(
        self, mock_is_instance_active, mock_input
    ):
        """
        Test that the URL is successfully returned on the second attempt after the first failure.
        """
        config = OpikConfigurator()
        config._ask_for_url()
        assert config.url == "http://valid-url.com"
        assert mock_is_instance_active.call_count == 2

    @patch(
        "builtins.input",
        side_effect=[
            "http://invalid-url.com",
            "http://invalid-url-2.com",
            "http://valid-url.com",
        ],
    )
    @patch(
        "opik.configurator.configure.OpikConfigurator._is_instance_active",
        side_effect=[False, False, True],
    )
    def test_ask_for_url_success_on_third_try(
        self, mock_is_instance_active, mock_input
    ):
        """
        Test that the URL is successfully returned on the third attempt after two failures.
        """
        config = OpikConfigurator()
        config._ask_for_url()
        assert config.url == "http://valid-url.com"
        assert mock_is_instance_active.call_count == 3

    @patch("builtins.input", side_effect=["http://invalid-url.com"] * 3)
    @patch(
        "opik.configurator.configure.OpikConfigurator._is_instance_active",
        return_value=False,
    )
    @patch("opik.configurator.configure.LOGGER.error")
    def test_ask_for_url_logging(
        self, mock_logger_error, mock_is_instance_active, mock_input
    ):
        """
        Test that errors are logged when the URL is not accessible.
        """
        with pytest.raises(ConfigurationError):
            config = OpikConfigurator()
            config._ask_for_url()

        assert mock_logger_error.call_count == 3
        mock_logger_error.assert_called_with(
            f"Opik is not accessible at http://invalid-url.com. Please try again,"
            f" the URL should follow a format similar to {OPIK_BASE_URL_LOCAL}"
        )


class TestAskForApiKey:
    @patch("opik.configurator.configure.is_interactive", return_value=True)
    @patch("opik.configurator.configure.getpass.getpass", return_value="valid_api_key")
    @patch(
        "opik.configurator.configure.OpikConfigurator._is_api_key_correct",
        return_value=True,
    )
    def test_ask_for_api_key_success(
        self, mock_is_api_key_correct, mock_getpass, mock_is_interactive
    ):
        """
        Test successful entry of a valid API key.
        """
        config = OpikConfigurator()
        config._ask_for_api_key()
        assert config.api_key == "valid_api_key"
        mock_is_api_key_correct.assert_called_once_with("valid_api_key")

    @patch("opik.configurator.configure.is_interactive", return_value=False)
    def test_ask_for_api_key_non_interactive_mode(self, mock_is_interactive):
        config = OpikConfigurator()

        with pytest.raises(ConfigurationError):
            config._ask_for_api_key()
        mock_is_interactive.assert_called_once()

    @patch("opik.configurator.configure.is_interactive", return_value=True)
    @patch(
        "opik.configurator.configure.getpass.getpass", return_value="invalid_api_key"
    )
    @patch(
        "opik.configurator.configure.OpikConfigurator._is_api_key_correct",
        return_value=False,
    )
    def test_ask_for_api_key_all_retries_fail(
        self, mock_is_api_key_correct, mock_getpass, mock_is_interactive
    ):
        """
        Test that after 3 invalid API key attempts, a ConfigurationError is raised.
        """
        with pytest.raises(ConfigurationError, match="API key is incorrect."):
            OpikConfigurator()._ask_for_api_key()

        assert mock_is_api_key_correct.call_count == 3

    @patch("opik.configurator.configure.is_interactive", return_value=True)
    @patch(
        "opik.configurator.configure.getpass.getpass",
        side_effect=["invalid_key", "valid_key"],
    )
    @patch(
        "opik.configurator.configure.OpikConfigurator._is_api_key_correct",
        side_effect=[False, True],
    )
    def test_ask_for_api_key_success_on_second_try(
        self, mock_is_api_key_correct, mock_getpass, mock_is_interactive
    ):
        """
        Test that the correct API key is entered on the second attempt after the first one is invalid.
        """
        config = OpikConfigurator()
        config._ask_for_api_key()
        assert config.api_key == "valid_key"
        assert mock_is_api_key_correct.call_count == 2

    @patch("opik.configurator.configure.is_interactive", return_value=True)
    @patch(
        "opik.configurator.configure.getpass.getpass",
        side_effect=["invalid_key1", "invalid_key2", "valid_key"],
    )
    @patch(
        "opik.configurator.configure.OpikConfigurator._is_api_key_correct",
        side_effect=[False, False, True],
    )
    def test_ask_for_api_key_success_on_third_try(
        self, mock_is_api_key_correct, mock_getpass, mock_is_interactive
    ):
        """
        Test that the correct API key is entered on the third attempt after two invalid attempts.
        """
        config = OpikConfigurator()
        config._ask_for_api_key()
        assert config.api_key == "valid_key"
        assert mock_is_api_key_correct.call_count == 3


class TestAskForWorkspace:
    @patch("opik.configurator.configure.is_interactive", return_value=True)
    @patch("builtins.input", return_value="valid_workspace")
    @patch(
        "opik.configurator.configure.OpikConfigurator._is_workspace_name_correct",
        return_value=True,
    )
    def test_ask_for_workspace_success(
        self, mock_is_workspace_name_correct, mock_input, mock_is_interactive
    ):
        """
        Test successful entry of a valid workspace name.
        """
        api_key = "valid_api_key"
        config = OpikConfigurator(api_key=api_key)
        config._ask_for_workspace()
        assert config.workspace == "valid_workspace"
        mock_is_workspace_name_correct.assert_called_once_with("valid_workspace")

    @patch("opik.configurator.configure.is_interactive", return_value=False)
    def test_ask_for_workspace_non_interactive_mode(self, mock_is_interactive):
        api_key = "valid_api_key"
        config = OpikConfigurator(api_key=api_key)

        with pytest.raises(ConfigurationError):
            config._ask_for_workspace()
        mock_is_interactive.assert_called_once()

    @patch("opik.configurator.configure.is_interactive", return_value=True)
    @patch("builtins.input", return_value="invalid_workspace")
    @patch(
        "opik.configurator.configure.OpikConfigurator._is_workspace_name_correct",
        return_value=False,
    )
    def test_ask_for_workspace_all_retries_fail(
        self, mock_is_workspace_name_correct, mock_input, mock_is_interactive
    ):
        """
        Test that after 3 invalid workspace name attempts, a ConfigurationError is raised.
        """
        api_key = "valid_api_key"

        with pytest.raises(
            ConfigurationError,
            match="User does not have access to the workspaces provided.",
        ):
            OpikConfigurator(api_key)._ask_for_workspace()

        assert mock_is_workspace_name_correct.call_count == 3

    @patch("opik.configurator.configure.is_interactive", return_value=True)
    @patch("builtins.input", side_effect=["invalid_workspace", "valid_workspace"])
    @patch(
        "opik.configurator.configure.OpikConfigurator._is_workspace_name_correct",
        side_effect=[False, True],
    )
    def test_ask_for_workspace_success_on_second_try(
        self, mock_is_workspace_name_correct, mock_input, mock_is_interactive
    ):
        """
        Test that the workspace name is successfully entered on the second attempt after the first one is invalid.
        """
        api_key = "valid_api_key"
        config = OpikConfigurator(api_key=api_key)
        config._ask_for_workspace()
        assert config.workspace == "valid_workspace"
        assert mock_is_workspace_name_correct.call_count == 2

    @patch("opik.configurator.configure.is_interactive", return_value=True)
    @patch(
        "builtins.input",
        side_effect=["invalid_workspace1", "invalid_workspace2", "valid_workspace"],
    )
    @patch(
        "opik.configurator.configure.OpikConfigurator._is_workspace_name_correct",
        side_effect=[False, False, True],
    )
    def test_ask_for_workspace_success_on_third_try(
        self, mock_is_workspace_name_correct, mock_input, mock_is_interactive
    ):
        """
        Test that the workspace name is successfully entered on the third attempt after two invalid attempts.
        """
        api_key = "valid_api_key"
        config = OpikConfigurator(api_key=api_key)
        config._ask_for_workspace()
        assert config.workspace == "valid_workspace"
        assert mock_is_workspace_name_correct.call_count == 3


class TestGetApiKey:
    def set_api_key(self):
        self.configurator.api_key = "new_api_key"

    @patch("opik.configurator.configure.OpikConfigurator._ask_for_api_key")
    def test_get_api_key_force_ask(self, mock_ask_for_api_key):
        """
        Test that when force=True and no API key is provided, the user is asked for an API key.
        """
        mock_ask_for_api_key.side_effect = self.set_api_key

        self.configurator = OpikConfigurator(api_key=None, force=True)
        needs_update = self.configurator._set_api_key()

        assert self.configurator.api_key == "new_api_key"
        assert needs_update is True
        mock_ask_for_api_key.assert_called_once()

    @patch("opik.configurator.configure.OpikConfigurator._ask_for_api_key")
    def test_get_api_key_ask_for_missing_key(self, mock_ask_for_api_key):
        """
        Test that when no API key is provided and none is present in the config, the user is asked for an API key.
        """
        mock_ask_for_api_key.side_effect = self.set_api_key

        self.configurator = OpikConfigurator(api_key=None, force=False)
        needs_update = self.configurator._set_api_key()

        assert self.configurator.api_key == "new_api_key"
        assert needs_update is True
        mock_ask_for_api_key.assert_called_once()

    @patch("opik.configurator.configure.opik.config.OpikConfig")
    def test_get_api_key_use_config_key(self, mock_opik_config):
        """
        Test that the API key is taken from the current config when provided and force=False.
        """
        mock_config_instance = MagicMock()
        mock_config_instance.api_key = "new_api_key"
        mock_opik_config.return_value = mock_config_instance

        configurator = OpikConfigurator(api_key=None, force=False)
        needs_update = configurator._set_api_key()

        assert configurator.api_key == "new_api_key"
        assert needs_update is False

    @patch(
        "opik.configurator.configure.OpikConfigurator._is_api_key_correct",
        return_value=True,
    )
    def test_get_api_key_provided_key(self, mock_is_api_key_correct):
        """
        Test that the user-provided API key is used directly if it's passed in.
        """
        configurator = OpikConfigurator(
            api_key="config_api_key", url=OPIK_BASE_URL_CLOUD, force=True
        )
        needs_update = configurator._set_api_key()

        mock_is_api_key_correct.assert_called_once()

        assert configurator.api_key == "config_api_key"
        assert needs_update is True


class TestGetWorkspace:
    @patch("opik.configurator.configure.opik.config.OpikConfig")
    @patch(
        "opik.configurator.configure.OpikConfigurator._is_workspace_name_correct",
        return_value=True,
    )
    def test_get_workspace_user_provided_valid_force(
        self, mock_is_workspace_name_correct, mock_opik_config
    ):
        """
        Test that the workspace provided by the user is valid and used.
        """
        mock_config_instance = MagicMock()
        mock_config_instance.workspace = "existing_workspace"
        mock_opik_config.return_value = mock_config_instance

        configurator = OpikConfigurator(
            workspace="new_workspace", force=True, api_key="valid_api_key"
        )

        needs_update = configurator._set_workspace()

        assert configurator.workspace == "new_workspace"
        assert needs_update is True
        mock_is_workspace_name_correct.assert_called_once_with("new_workspace")

    @patch("opik.configurator.configure.opik.config.OpikConfig")
    @patch(
        "opik.configurator.configure.OpikConfigurator._is_workspace_name_correct",
        return_value=True,
    )
    def test_get_workspace_user_provided_valid_not_force(
        self, mock_is_workspace_name_correct, mock_opik_config
    ):
        """
        Test that the workspace provided by the user is valid and used.
        """
        mock_config_instance = MagicMock()
        mock_config_instance.workspace = "existing_workspace"
        mock_opik_config.return_value = mock_config_instance

        configurator = OpikConfigurator(
            workspace="new_workspace", force=False, api_key="valid_api_key"
        )

        needs_update = configurator._set_workspace()

        assert configurator.workspace == "new_workspace"
        assert needs_update is False
        mock_is_workspace_name_correct.assert_called_once_with("new_workspace")

    @patch("opik.configurator.configure.opik.config.OpikConfig")
    @patch(
        "opik.configurator.configure.OpikConfigurator._is_workspace_name_correct",
        return_value=False,
    )
    def test_get_workspace_user_provided_invalid(
        self, mock_is_workspace_name_correct, mock_opik_config
    ):
        """
        Test that a ConfigurationError is raised if the user-provided workspace is invalid.
        """
        mock_config_instance = MagicMock()
        mock_config_instance.workspace = "existing_workspace"
        mock_opik_config.return_value = mock_config_instance

        configurator = OpikConfigurator(
            workspace="invalid_workspace", force=False, api_key="valid_api_key"
        )

        with pytest.raises(ConfigurationError):
            configurator._set_workspace()

        mock_is_workspace_name_correct.assert_called_once_with("invalid_workspace")

    @patch("opik.configurator.configure.opik.config.OpikConfig")
    def test_get_workspace_use_config(self, mock_opik_config):
        """
        Test that the workspace from the current config is used when no workspace is provided and not forced.
        """
        current_config = OpikConfig(workspace="configured_workspace")
        mock_opik_config.return_value = current_config

        configurator = OpikConfigurator(
            workspace=None, force=False, api_key="valid_api_key"
        )
        needs_update = configurator._set_workspace()

        assert configurator.workspace == "configured_workspace"
        assert needs_update is False

    @patch("opik.configurator.configure.opik.config.OpikConfig")
    @patch(
        "opik.configurator.configure.OpikConfigurator._get_default_workspace",
        return_value="default_workspace",
    )
    @patch("opik.configurator.configure.ask_user_for_approval", return_value=True)
    def test_get_workspace_accept_default(
        self, mock_ask_user_for_approval, mock_get_default_workspace, mock_opik_config
    ):
        """
        Test that the user accepts the default workspace.
        """
        current_config = OpikConfig(workspace=OPIK_WORKSPACE_DEFAULT_NAME)
        mock_opik_config.return_value = current_config

        configurator = OpikConfigurator(
            workspace=None, force=False, api_key="valid_api_key"
        )
        needs_update = configurator._set_workspace()

        assert configurator.workspace == "default_workspace"
        assert needs_update is True
        mock_get_default_workspace.assert_called_once_with()
        mock_ask_user_for_approval.assert_called_once_with(
            'Do you want to use "default_workspace" workspace? (Y/n)'
        )

    @patch("opik.configurator.configure.opik.config.OpikConfig")
    @patch(
        "opik.configurator.configure.OpikConfigurator._get_default_workspace",
        return_value="default_workspace",
    )
    @patch("opik.configurator.configure.ask_user_for_approval", return_value=False)
    @patch("opik.configurator.configure.OpikConfigurator._ask_for_workspace")
    def test_get_workspace_choose_different(
        self,
        mock_ask_for_workspace,
        mock_ask_user_for_approval,
        mock_get_default_workspace,
        mock_opik_config,
    ):
        """
        Test that the user declines the default workspace and chooses a new one.
        """

        def set_workspace():
            configurator.workspace = "new_workspace"

        mock_ask_for_workspace.side_effect = set_workspace

        current_config = OpikConfig(workspace=OPIK_WORKSPACE_DEFAULT_NAME)
        mock_opik_config.return_value = current_config

        configurator = OpikConfigurator(
            workspace=None, force=False, api_key="valid_api_key"
        )
        needs_update = configurator._set_workspace()

        assert configurator.workspace == "new_workspace"
        assert needs_update is True
        mock_get_default_workspace.assert_called_once_with()
        mock_ask_user_for_approval.assert_called_once_with(
            'Do you want to use "default_workspace" workspace? (Y/n)'
        )
        mock_ask_for_workspace.assert_called_once_with()


class TestConfigureCloud:
    @patch("opik.configurator.configure.OpikConfigurator._set_api_key")
    @patch("opik.configurator.configure.OpikConfigurator._set_workspace")
    @patch("opik.configurator.configure.OpikConfigurator._update_config")
    def test_configure_cloud_with_update(
        self, mock_update_config, mock_set_workspace, mock_set_api_key
    ):
        """
        Test that the configuration is updated when both API key and workspace require updates.
        """

        def set_workspace():
            configurator.workspace = "valid_workspace"
            return True

        def set_api_key():
            configurator.api_key = "valid_api_key"
            return True

        mock_set_api_key.side_effect = set_api_key
        mock_set_workspace.side_effect = set_workspace

        configurator = OpikConfigurator(api_key=None, workspace=None, force=False)
        configurator._configure_cloud()

        mock_set_api_key.assert_called_once()
        mock_set_workspace.assert_called_once()
        mock_update_config.assert_called_once()

        assert configurator.api_key == "valid_api_key"
        assert configurator.url == OPIK_BASE_URL_CLOUD
        assert configurator.workspace == "valid_workspace"

    @patch("opik.configurator.configure.OpikConfigurator._set_api_key")
    @patch("opik.configurator.configure.OpikConfigurator._set_workspace")
    @patch("opik.configurator.configure.LOGGER.info")
    @patch("opik.configurator.configure.opik.config.OpikConfig")
    @patch("opik.configurator.configure.OpikConfigurator._update_config")
    def test_configure_cloud_no_update_needed(
        self,
        mock_update_config,
        mock_opik_config,
        mock_logger_info,
        mock_set_workspace,
        mock_set_api_key,
    ):
        """
        Test that no configuration update happens when both API key and workspace are already set.
        """

        def set_workspace():
            configurator.workspace = "valid_workspace"
            return False

        def set_api_key():
            configurator.api_key = "valid_api_key"
            return False

        mock_set_api_key.side_effect = set_api_key
        mock_set_workspace.side_effect = set_workspace

        # Mock the config file path to return a specific path
        mock_config_instance = MagicMock()
        mock_config_instance.config_file_fullpath = Path("/some/path/.opik.config")
        mock_opik_config.return_value = mock_config_instance

        # Call the function
        configurator = OpikConfigurator(
            api_key="valid_api_key", workspace="valid_workspace", force=False
        )
        configurator._configure_cloud()

        # Ensure API key and workspace were checked
        mock_set_api_key.assert_called_once()
        mock_set_workspace.assert_called_once()

        # Check config file wasn't overwritten, but session updated
        mock_update_config.assert_called_once_with(save_to_file=False)

        # Check the logging message
        mock_logger_info.assert_called_with(
            "Opik is already configured. You can check the settings by viewing the config file at %s",
            Path("/some/path/.opik.config"),
        )

    @patch("opik.configurator.configure.OpikConfigurator._set_api_key")
    @patch("opik.configurator.configure.OpikConfigurator._set_workspace")
    @patch("opik.configurator.configure.OpikConfigurator._update_config")
    def test_configure_cloud_api_key_updated(
        self, mock_update_config, mock_set_workspace, mock_set_api_key
    ):
        """
        Test that the configuration is updated when only the API key changes.
        """

        def set_workspace():
            configurator.workspace = "configured_workspace"
            return False

        def set_api_key():
            configurator.api_key = "new_api_key"
            return True

        mock_set_api_key.side_effect = set_api_key
        mock_set_workspace.side_effect = set_workspace

        configurator = OpikConfigurator(
            api_key=None, workspace="configured_workspace", force=False
        )
        configurator._configure_cloud()

        mock_set_api_key.assert_called_once()
        mock_set_workspace.assert_called_once()
        mock_update_config.assert_called_once()

        assert configurator.api_key == "new_api_key"
        assert configurator.url == OPIK_BASE_URL_CLOUD
        assert configurator.workspace == "configured_workspace"

    @patch("opik.configurator.configure.OpikConfigurator._set_api_key")
    @patch("opik.configurator.configure.OpikConfigurator._set_workspace")
    @patch("opik.configurator.configure.OpikConfigurator._update_config")
    def test_configure_cloud_workspace_updated(
        self, mock_update_config, mock_set_workspace, mock_set_api_key
    ):
        """
        Test that the configuration is updated when only the workspace changes.
        """

        def set_workspace():
            configurator.workspace = "new_workspace"
            return False

        def set_api_key():
            configurator.api_key = "valid_api_key"
            return True

        mock_set_api_key.side_effect = set_api_key
        mock_set_workspace.side_effect = set_workspace

        configurator = OpikConfigurator(
            api_key="valid_api_key", workspace=None, force=False
        )
        configurator._configure_cloud()

        mock_set_api_key.assert_called_once()
        mock_set_workspace.assert_called_once()
        mock_update_config.assert_called_once()

        assert configurator.api_key == "valid_api_key"
        assert configurator.url == OPIK_BASE_URL_CLOUD
        assert configurator.workspace == "new_workspace"


class TestConfigureLocal:
    @patch("opik.configurator.configure.is_interactive", return_value=True)
    @patch("opik.configurator.configure.OpikConfigurator._ask_for_url")
    @patch(
        "opik.configurator.configure.OpikConfigurator._is_instance_active",
        return_value=False,
    )
    @patch("opik.configurator.configure.OpikConfigurator._update_config")
    def test_configure_local_asks_for_url(
        self,
        mock_update_config,
        mock_is_instance_active,
        mock_ask_for_url,
        mock_is_interactive,
    ):
        """
        Test that the function asks for a URL if no local instance is active and no URL is provided.
        """

        def set_url():
            configurator.url = "http://user-provided-url.com"

        mock_ask_for_url.side_effect = set_url

        configurator = OpikConfigurator(url=None, force=False)
        configurator._configure_local()

        mock_ask_for_url.assert_called_once()
        mock_update_config.assert_called_once()

        assert configurator.api_key is None
        assert configurator.url == "http://user-provided-url.com"
        assert configurator.workspace == OPIK_WORKSPACE_DEFAULT_NAME

    @patch("opik.configurator.configure.is_interactive", return_value=False)
    @patch("opik.configurator.configure.OpikConfigurator._ask_for_url")
    @patch(
        "opik.configurator.configure.OpikConfigurator._is_instance_active",
        return_value=False,
    )
    @patch("opik.configurator.configure.OpikConfigurator._update_config")
    def test_configure_local_asks_for_url__non_interactive(
        self,
        mock_update_config,
        mock_is_instance_active,
        mock_ask_for_url,
        mock_is_interactive,
    ):
        """
        Test that the function asks for a URL if no local instance is active and no URL is provided.
        """

        def set_url():
            configurator.url = "http://user-provided-url.com"

        mock_ask_for_url.side_effect = set_url

        configurator = OpikConfigurator(url=None, force=False)

        with pytest.raises(ConfigurationError):
            configurator._configure_local()

        mock_ask_for_url.assert_not_called()
        mock_update_config.assert_not_called()

    @patch("opik.configurator.configure.OpikConfigurator._ask_for_url")
    @patch(
        "opik.configurator.configure.OpikConfigurator._is_instance_active",
        return_value=True,
    )
    @patch("opik.configurator.configure.OpikConfigurator._update_config")
    def test_configure_local_with_provided_url(
        self, mock_update_config, mock_is_instance_active, mock_ask_for_url
    ):
        """
        Test that the function configures the provided URL if it is active.
        """
        configurator = OpikConfigurator(
            url="http://custom-local-instance.com", force=False
        )
        configurator._configure_local()

        mock_ask_for_url.assert_not_called()
        mock_is_instance_active.assert_called_once_with(
            "http://custom-local-instance.com"
        )
        mock_update_config.assert_called_once()

        assert configurator.api_key is None
        assert configurator.url == "http://custom-local-instance.com"
        assert configurator.workspace == OPIK_WORKSPACE_DEFAULT_NAME

    @patch("opik.configurator.configure.OpikConfigurator._ask_for_url")
    @patch(
        "opik.configurator.configure.OpikConfigurator._is_instance_active",
        return_value=True,
    )
    @patch("opik.configurator.configure.opik.config.OpikConfig")
    @patch("opik.configurator.configure.LOGGER.info")
    def test_configure_local_no_update_needed(
        self,
        mock_logger_info,
        mock_opik_config,
        mock_is_instance_active,
        mock_ask_for_url,
    ):
        """
        Test that no update happens if the local instance is already configured and force=False.
        """
        mock_config_instance = MagicMock()
        mock_config_instance.url_override = OPIK_BASE_URL_LOCAL
        mock_opik_config.return_value = mock_config_instance

        configurator = OpikConfigurator(url=None, force=False)
        configurator._configure_local()

        mock_ask_for_url.assert_not_called()
        mock_is_instance_active.assert_called_once_with(OPIK_BASE_URL_LOCAL)
        mock_logger_info.assert_called_once_with(
            f"Opik is already configured to local instance at {OPIK_BASE_URL_LOCAL}."
        )

    @patch("opik.configurator.configure.is_interactive", return_value=True)
    @patch("opik.configurator.configure.ask_user_for_approval", return_value=True)
    @patch(
        "opik.configurator.configure.OpikConfigurator._is_instance_active",
        return_value=True,
    )
    @patch("opik.configurator.configure.OpikConfigurator._update_config")
    def test_configure_local_uses_local_instance(
        self,
        mock_update_config,
        mock_is_instance_active,
        mock_ask_user_for_approval,
        mock_is_interactive,
    ):
        """
        Test that the function configures the local instance when found and user approves.
        """
        configurator = OpikConfigurator(url=None, force=False)
        configurator._configure_local()

        mock_ask_user_for_approval.assert_called_once_with(
            f"Found local Opik instance on: {OPIK_BASE_URL_LOCAL}, do you want to use it? (Y/n)"
        )
        mock_update_config.assert_called_once_with()

        assert configurator.api_key is None
        assert configurator.url == OPIK_BASE_URL_LOCAL
        assert configurator.workspace == OPIK_WORKSPACE_DEFAULT_NAME

    @patch("opik.configurator.configure.is_interactive", return_value=False)
    @patch("opik.configurator.configure.ask_user_for_approval", return_value=True)
    @patch(
        "opik.configurator.configure.OpikConfigurator._is_instance_active",
        return_value=True,
    )
    @patch("opik.configurator.configure.OpikConfigurator._update_config")
    def test_configure_local_uses_local_instance__non_interactive(
        self,
        mock_update_config,
        mock_is_instance_active,
        mock_ask_user_for_approval,
        mock_is_interactive,
    ):
        """
        Test that the function configures the local instance when found and user approves.
        """
        configurator = OpikConfigurator(url=None, force=False)
        with pytest.raises(ConfigurationError):
            configurator._configure_local()

        mock_ask_user_for_approval.assert_not_called()
        mock_update_config.assert_not_called()

    @patch("opik.configurator.configure.is_interactive", return_value=True)
    @patch("opik.configurator.configure.ask_user_for_approval", return_value=False)
    @patch("opik.configurator.configure.OpikConfigurator._ask_for_url")
    @patch(
        "opik.configurator.configure.OpikConfigurator._is_instance_active",
        return_value=True,
    )
    @patch("opik.configurator.configure.OpikConfigurator._update_config")
    def test_configure_local_user_declines_local_instance(
        self,
        mock_update_config,
        mock_is_instance_active,
        mock_ask_for_url,
        mock_ask_user_for_approval,
        mock_is_interactive,
    ):
        """
        Test that if the user declines using the local instance, they are prompted for a URL.
        """

        def set_url():
            configurator.url = "http://user-provided-url.com"

        mock_ask_for_url.side_effect = set_url

        configurator = OpikConfigurator(url=None, force=False)
        configurator._configure_local()

        mock_ask_user_for_approval.assert_called_once_with(
            f"Found local Opik instance on: {OPIK_BASE_URL_LOCAL}, do you want to use it? (Y/n)"
        )
        mock_ask_for_url.assert_called_once()
        mock_update_config.assert_called_once()

        assert configurator.api_key is None
        assert configurator.url == "http://user-provided-url.com"
        assert configurator.workspace == OPIK_WORKSPACE_DEFAULT_NAME

    @patch("opik.configurator.configure.is_interactive", return_value=False)
    @patch("opik.configurator.configure.ask_user_for_approval", return_value=False)
    @patch("opik.configurator.configure.OpikConfigurator._ask_for_url")
    @patch(
        "opik.configurator.configure.OpikConfigurator._is_instance_active",
        return_value=True,
    )
    @patch("opik.configurator.configure.OpikConfigurator._update_config")
    def test_configure_local_user_declines_local_instance__non_interactive(
        self,
        mock_update_config,
        mock_is_instance_active,
        mock_ask_for_url,
        mock_ask_user_for_approval,
        mock_is_interactive,
    ):
        """
        Test that if the user declines using the local instance, they are prompted for a URL.
        """

        def set_url():
            configurator.url = "http://user-provided-url.com"

        mock_ask_for_url.side_effect = set_url

        configurator = OpikConfigurator(url=None, force=False)

        with pytest.raises(ConfigurationError):
            configurator._configure_local()

        mock_ask_for_url.assert_not_called()
        mock_ask_user_for_approval.assert_not_called()
        mock_update_config.assert_not_called()
