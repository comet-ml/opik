from unittest.mock import MagicMock, patch

import httpx
from click.testing import CliRunner

from opik.cli.main import cli
from opik.cli.pairing import PairingResult, RunnerType
from opik.rest_api.core.api_error import ApiError


class TestConnect:
    @patch("opik.cli._run.RunnerTUI")
    @patch("opik.cli._run.launch_supervisor")
    @patch("opik.cli._run.run_pairing")
    @patch("opik.cli._run.Opik")
    def test_connect__with_project__calls_pairing_and_supervisor(
        self, mock_opik_cls, mock_run_pairing, mock_launch, mock_tui_cls
    ):
        client = MagicMock()
        client.config.url_override = "https://api.test/"
        mock_opik_cls.return_value = client

        mock_run_pairing.return_value = PairingResult(
            runner_id="r-abc",
            project_name="my-proj",
            project_id="p-123",
            bridge_key=b"\x00" * 32,
        )

        runner = CliRunner()
        result = runner.invoke(cli, ["connect", "--project", "my-proj"])
        assert result.exit_code == 0

        mock_run_pairing.assert_called_once()
        call_kwargs = mock_run_pairing.call_args[1]
        assert call_kwargs["project_name"] == "my-proj"
        assert call_kwargs["runner_type"] == RunnerType.CONNECT

        mock_launch.assert_called_once()
        launch_kwargs = mock_launch.call_args[1]
        assert launch_kwargs["command"] is None

    def test_connect__no_project__shows_error(self):
        runner = CliRunner()
        result = runner.invoke(cli, ["connect"])
        assert result.exit_code == 2
        assert "--project" in result.output

    @patch("opik.cli._run.RunnerTUI")
    @patch("opik.cli._run.launch_supervisor")
    @patch("opik.cli._run.run_pairing")
    @patch("opik.cli._run.Opik")
    def test_connect__network_failure__shows_clean_error(
        self, mock_opik_cls, mock_run_pairing, mock_launch, mock_tui_cls
    ):
        client = MagicMock()
        client.config.url_override = "https://api.test/"
        mock_opik_cls.return_value = client

        mock_run_pairing.side_effect = httpx.ConnectError("Connection refused")

        runner = CliRunner()
        result = runner.invoke(cli, ["connect", "--project", "my-proj"])
        assert result.exit_code != 0
        assert "Could not connect to Opik at https://api.test/" in result.output

    @patch("opik.cli._run.RunnerTUI")
    @patch("opik.cli._run.launch_supervisor")
    @patch("opik.cli._run.run_pairing")
    @patch("opik.cli._run.Opik")
    def test_connect__api_error__shows_error_body(
        self, mock_opik_cls, mock_run_pairing, mock_launch, mock_tui_cls
    ):
        client = MagicMock()
        client.config.url_override = "https://api.test/"
        mock_opik_cls.return_value = client

        mock_run_pairing.side_effect = ApiError(status_code=409, body="conflict")

        runner = CliRunner()
        result = runner.invoke(cli, ["connect", "--project", "my-proj"])
        assert result.exit_code != 0
        assert "conflict" in result.output

    @patch("opik.cli._run.RunnerTUI")
    @patch("opik.cli._run.launch_supervisor")
    @patch("opik.cli._run.run_pairing")
    @patch("opik.cli._run.Opik")
    def test_connect__tui_stopped_on_pairing_failure(
        self, mock_opik_cls, mock_run_pairing, mock_launch, mock_tui_cls
    ):
        client = MagicMock()
        client.config.url_override = "https://api.test/"
        mock_opik_cls.return_value = client

        mock_run_pairing.side_effect = ApiError(status_code=500, body="boom")

        runner = CliRunner()
        runner.invoke(cli, ["connect", "--project", "my-proj"])

        tui_instance = mock_tui_cls.return_value
        tui_instance.stop.assert_called_once()
