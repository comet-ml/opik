from unittest.mock import MagicMock, patch

import httpx
from click.testing import CliRunner

from opik.cli.main import cli
from opik.rest_api.types.local_runner_connect_response import LocalRunnerConnectResponse


class TestConnect:
    @patch("opik.cli.connect.RunnerTUI")
    @patch("opik.cli.connect.Supervisor")
    @patch("opik.cli.connect.Opik")
    def test_connect__with_pair_code__calls_connect_runner(
        self, mock_opik_cls, mock_supervisor_cls, mock_tui_cls
    ):
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
        result = runner.invoke(cli, ["connect", "--pair", "ABCD", "echo", "hello"])
        assert result.exit_code == 0

        call_kwargs = api.runners.connect_runner.call_args[1]
        assert call_kwargs["pairing_code"] == "ABCD"

    @patch("opik.cli.connect.RunnerTUI")
    @patch("opik.cli.connect.Supervisor")
    @patch("opik.cli.connect.Opik")
    def test_connect__with_command__creates_supervisor(
        self, mock_opik_cls, mock_supervisor_cls, mock_tui_cls
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

        mock_supervisor_cls.assert_called_once()
        call_kwargs = mock_supervisor_cls.call_args[1]
        assert call_kwargs["command"] == ["python", "myapp.py"]
        assert call_kwargs["runner_id"] == "r-xyz"
        env = call_kwargs["env"]
        assert env["OPIK_RUNNER_MODE"] == "true"
        assert env["OPIK_RUNNER_ID"] == "r-xyz"
        assert env["OPIK_PROJECT_NAME"] == "proj"

        tui_instance = mock_tui_cls.return_value
        assert call_kwargs["on_child_restart"] == tui_instance.child_restarted
        assert call_kwargs["on_error"] == tui_instance.error

        mock_supervisor_cls.return_value.run.assert_called_once()

    @patch("opik.cli.connect.RunnerTUI")
    @patch("opik.cli.connect.Supervisor")
    @patch("opik.cli.connect.Opik")
    def test_connect__network_failure__shows_clean_error(
        self, mock_opik_cls, mock_supervisor_cls, mock_tui_cls
    ):
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
        result = runner.invoke(cli, ["connect", "--pair", "CODE", "echo", "hello"])
        assert result.exit_code != 0
        assert "Could not connect to Opik at https://api.test" in result.output

    @patch("opik.cli.connect.RunnerTUI")
    @patch("opik.cli.connect.Supervisor")
    @patch("opik.cli.connect.Opik")
    def test_connect__no_command__standalone_mode(
        self, mock_opik_cls, mock_supervisor_cls, mock_tui_cls
    ):
        client = MagicMock()
        api = MagicMock()
        api.runners.connect_runner.return_value = LocalRunnerConnectResponse(
            runner_id="r-standalone",
            project_name="proj",
        )
        client.rest_client = api
        mock_opik_cls.return_value = client

        runner = CliRunner()
        result = runner.invoke(cli, ["connect", "--pair", "CODE"])
        assert result.exit_code == 0

        mock_supervisor_cls.assert_called_once()
        call_kwargs = mock_supervisor_cls.call_args[1]
        assert call_kwargs["command"] is None

    def test_connect__no_pair_code__shows_error(self):
        runner = CliRunner()
        result = runner.invoke(cli, ["connect", "echo", "hello"])
        assert result.exit_code == 2
        assert "--pair" in result.output
