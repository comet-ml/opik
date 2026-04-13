from unittest.mock import MagicMock, patch

import httpx
from click.testing import CliRunner

from opik.cli.main import cli
from opik.cli.pairing import PairingResult, RunnerType
from opik.rest_api.core.api_error import ApiError


class TestEndpoint:
    @patch("opik.runner.snapshot.has_entrypoint", return_value=True)
    @patch("opik.cli._run.RunnerTUI")
    @patch("opik.cli._run.launch_supervisor")
    @patch("opik.cli._run.run_pairing")
    @patch("opik.cli._run.Opik")
    def test_endpoint__with_command__calls_pairing_and_supervisor(
        self, mock_opik_cls, mock_run_pairing, mock_launch, mock_tui_cls, _mock_ep
    ):
        client = MagicMock()
        client.config.url_override = "https://api.test/"
        mock_opik_cls.return_value = client

        mock_run_pairing.return_value = PairingResult(
            runner_id="r-xyz",
            project_name="my-proj",
            project_id="p-123",
            bridge_key=b"\x00" * 32,
        )

        runner = CliRunner()
        result = runner.invoke(
            cli, ["endpoint", "--project", "my-proj", "--", "echo", "hello"]
        )
        assert result.exit_code == 0

        mock_run_pairing.assert_called_once()
        call_kwargs = mock_run_pairing.call_args[1]
        assert call_kwargs["project_name"] == "my-proj"
        assert call_kwargs["runner_type"] == RunnerType.ENDPOINT

        mock_launch.assert_called_once()
        launch_kwargs = mock_launch.call_args[1]
        assert launch_kwargs["command"] == ["echo", "hello"]

    def test_endpoint__no_project__shows_error(self):
        runner = CliRunner()
        result = runner.invoke(cli, ["endpoint", "--", "echo", "hello"])
        assert result.exit_code == 2
        assert "--project" in result.output

    def test_endpoint__no_command__shows_error(self):
        runner = CliRunner()
        result = runner.invoke(cli, ["endpoint", "--project", "my-proj"])
        assert result.exit_code == 2

    def test_endpoint__nonexistent_binary__shows_error(self):
        runner = CliRunner()
        result = runner.invoke(
            cli,
            ["endpoint", "--project", "my-proj", "--", "nonexistent-binary-xyz-12345"],
        )
        assert result.exit_code == 2
        assert "not found" in result.output.lower()

    @patch("opik.runner.snapshot.has_entrypoint", return_value=True)
    @patch("opik.cli._run.RunnerTUI")
    @patch("opik.cli._run.launch_supervisor")
    @patch("opik.cli._run.run_pairing")
    @patch("opik.cli._run.Opik")
    def test_endpoint__network_failure__shows_clean_error(
        self, mock_opik_cls, mock_run_pairing, mock_launch, mock_tui_cls, _mock_ep
    ):
        client = MagicMock()
        client.config.url_override = "https://api.test/"
        mock_opik_cls.return_value = client

        mock_run_pairing.side_effect = httpx.ConnectError("Connection refused")

        runner = CliRunner()
        result = runner.invoke(
            cli, ["endpoint", "--project", "my-proj", "--", "echo", "hello"]
        )
        assert result.exit_code != 0
        assert "Could not connect to Opik at https://api.test/" in result.output

    @patch("opik.runner.snapshot.has_entrypoint", return_value=True)
    @patch("opik.cli._run.RunnerTUI")
    @patch("opik.cli._run.launch_supervisor")
    @patch("opik.cli._run.run_pairing")
    @patch("opik.cli._run.Opik")
    def test_endpoint__tui_stopped_on_pairing_failure(
        self, mock_opik_cls, mock_run_pairing, mock_launch, mock_tui_cls, _mock_ep
    ):
        client = MagicMock()
        client.config.url_override = "https://api.test/"
        mock_opik_cls.return_value = client

        mock_run_pairing.side_effect = ApiError(status_code=500, body="boom")

        runner = CliRunner()
        runner.invoke(cli, ["endpoint", "--project", "my-proj", "--", "echo", "hello"])

        tui_instance = mock_tui_cls.return_value
        tui_instance.stop.assert_called_once()
