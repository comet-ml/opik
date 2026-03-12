from unittest.mock import MagicMock, patch

import httpx
from click.testing import CliRunner

from opik.cli.main import cli
from opik.rest_api.types.local_runner_connect_response import LocalRunnerConnectResponse


class TestConnect:
    @patch("opik.cli.connect.Opik")
    def test_connect__with_pair_code__calls_connect_runner(self, mock_opik_cls):
        client = MagicMock()
        api = MagicMock()
        api.runners.connect_runner.return_value = LocalRunnerConnectResponse(
            runner_id="r-abc",
            workspace_id="ws-1",
            project_id="p-1",
            project_name="my-project",
        )
        client.rest_client = api
        mock_opik_cls.return_value = client

        runner = CliRunner()
        result = runner.invoke(cli, ["connect", "--pair", "ABCD"])
        assert result.exit_code == 0
        assert "r-abc" in result.output

        call_kwargs = api.runners.connect_runner.call_args[1]
        assert call_kwargs["pairing_code"] == "ABCD"

    @patch("opik.cli.connect.os.execvpe")
    @patch("opik.cli.connect.Opik")
    def test_connect__with_command__sets_env_and_execs(
        self, mock_opik_cls, mock_execvpe
    ):
        client = MagicMock()
        api = MagicMock()
        api.runners.connect_runner.return_value = LocalRunnerConnectResponse(
            runner_id="r-xyz",
            project_name="proj",
        )
        client.rest_client = api
        mock_opik_cls.return_value = client

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

    @patch("opik.cli.connect.Opik")
    def test_connect__network_failure__shows_clean_error(self, mock_opik_cls):
        client = MagicMock()
        config = MagicMock()
        config.url_override = "https://api.test"
        client.config = config
        api = MagicMock()
        api.runners.connect_runner.side_effect = httpx.ConnectError(
            "Connection refused"
        )
        client.rest_client = api
        mock_opik_cls.return_value = client

        runner = CliRunner()
        result = runner.invoke(cli, ["connect"])
        assert result.exit_code != 0
        assert "Could not connect to Opik at https://api.test" in result.output
