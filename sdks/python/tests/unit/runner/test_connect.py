from unittest.mock import MagicMock, patch

import httpx
from click.testing import CliRunner

from opik.cli.main import cli
from opik.rest_api.types.local_runner_connect_response import LocalRunnerConnectResponse


class TestConnect:
    @patch("opik.cli.connect.OpikApi")
    @patch("opik.cli.connect.httpx_client")
    @patch("opik.cli.connect.OpikConfig")
    def test_connect__with_pair_code__calls_connect_runner(
        self, mock_config_cls, mock_httpx_client, mock_api_cls
    ):
        config = MagicMock()
        config.url_override = "https://api.test"
        config.api_key = "key-123"
        config.workspace = "ws"
        mock_config_cls.return_value = config

        mock_httpx_client.get.return_value = MagicMock()

        api = MagicMock()
        api.runners.connect_runner.return_value = LocalRunnerConnectResponse(
            runner_id="r-abc",
            workspace_id="ws-1",
            project_id="p-1",
            project_name="my-project",
        )
        mock_api_cls.return_value = api

        runner = CliRunner()
        result = runner.invoke(cli, ["connect", "--pair", "ABCD"])
        assert result.exit_code == 0
        assert "r-abc" in result.output

        call_kwargs = api.runners.connect_runner.call_args[1]
        assert call_kwargs["pairing_code"] == "ABCD"

    @patch("opik.cli.connect.os.execvpe")
    @patch("opik.cli.connect.OpikApi")
    @patch("opik.cli.connect.httpx_client")
    @patch("opik.cli.connect.OpikConfig")
    def test_connect__with_command__sets_env_and_execs(
        self, mock_config_cls, mock_httpx_client, mock_api_cls, mock_execvpe
    ):
        config = MagicMock()
        config.url_override = "https://api.test"
        config.api_key = "key"
        config.workspace = "ws"
        mock_config_cls.return_value = config

        mock_httpx_client.get.return_value = MagicMock()

        api = MagicMock()
        api.runners.connect_runner.return_value = LocalRunnerConnectResponse(
            runner_id="r-xyz",
            project_name="proj",
        )
        mock_api_cls.return_value = api

        runner = CliRunner()
        result = runner.invoke(cli, ["connect", "--pair", "CODE", "python", "myapp.py"])
        assert result.exit_code == 0

        mock_execvpe.assert_called_once()
        args = mock_execvpe.call_args
        assert args[0][0] == "python"
        assert args[0][1] == ["python", "myapp.py"]
        env = args[0][2]
        assert env["OPIK_RUNNER_MODE"] == "true"
        assert env["OPIK_RUNNER_ID"] == "r-xyz"
        assert env["OPIK_PROJECT_NAME"] == "proj"

    @patch("opik.cli.connect.OpikApi")
    @patch("opik.cli.connect.httpx_client")
    @patch("opik.cli.connect.OpikConfig")
    def test_connect__network_failure__shows_clean_error(
        self, mock_config_cls, mock_httpx_client, mock_api_cls
    ):
        config = MagicMock()
        config.url_override = "https://api.test"
        config.api_key = "key"
        config.workspace = "ws"
        mock_config_cls.return_value = config

        mock_httpx_client.get.return_value = MagicMock()

        api = MagicMock()
        api.runners.connect_runner.side_effect = httpx.ConnectError(
            "Connection refused"
        )
        mock_api_cls.return_value = api

        runner = CliRunner()
        result = runner.invoke(cli, ["connect"])
        assert result.exit_code != 0
        assert "Could not connect to Opik at https://api.test" in result.output
