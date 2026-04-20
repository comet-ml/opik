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

    @patch("opik.cli._run.RunnerTUI")
    @patch("opik.cli._run.launch_supervisor")
    @patch("opik.cli._run.run_pairing")
    @patch("opik.cli._run.Opik")
    def test_connect__workspace_and_api_key_passed__forwarded_to_opik_constructor(
        self, mock_opik_cls, mock_run_pairing, mock_launch, mock_tui_cls
    ):
        client = MagicMock()
        client.config.url_override = "https://api.test/"
        client.config.config_file_exists = True
        mock_opik_cls.return_value = client

        mock_run_pairing.return_value = PairingResult(
            runner_id="r-abc",
            project_name="my-proj",
            project_id="p-123",
            bridge_key=b"\x00" * 32,
        )

        runner = CliRunner()
        result = runner.invoke(
            cli,
            [
                "connect",
                "--project",
                "my-proj",
                "--workspace",
                "my-ws",
                "--api-key",
                "my-key",
            ],
        )
        assert result.exit_code == 0

        mock_opik_cls.assert_called_once_with(
            project_name="my-proj",
            api_key="my-key",
            workspace="my-ws",
            _show_misconfiguration_message=False,
        )

    @patch("opik.cli._run.RunnerTUI")
    @patch("opik.cli._run.launch_supervisor")
    @patch("opik.cli._run.run_pairing")
    @patch("opik.cli._run.Opik")
    def test_connect__local_api_key_overrides_global(
        self, mock_opik_cls, mock_run_pairing, mock_launch, mock_tui_cls
    ):
        client = MagicMock()
        client.config.url_override = "https://api.test/"
        client.config.config_file_exists = True
        mock_opik_cls.return_value = client

        mock_run_pairing.return_value = PairingResult(
            runner_id="r-abc",
            project_name="my-proj",
            project_id="p-123",
            bridge_key=b"\x00" * 32,
        )

        runner = CliRunner()
        result = runner.invoke(
            cli,
            [
                "--api-key",
                "global-key",
                "connect",
                "--project",
                "my-proj",
                "--api-key",
                "local-key",
            ],
        )
        assert result.exit_code == 0

        mock_opik_cls.assert_called_once_with(
            project_name="my-proj",
            api_key="local-key",
            workspace=None,
            _show_misconfiguration_message=False,
        )

    @patch("opik.cli._run.RunnerTUI")
    @patch("opik.cli._run.launch_supervisor")
    @patch("opik.cli._run.run_pairing")
    @patch("opik.cli._run.Opik")
    def test_connect__no_local_api_key__falls_back_to_global(
        self, mock_opik_cls, mock_run_pairing, mock_launch, mock_tui_cls
    ):
        client = MagicMock()
        client.config.url_override = "https://api.test/"
        client.config.config_file_exists = True
        mock_opik_cls.return_value = client

        mock_run_pairing.return_value = PairingResult(
            runner_id="r-abc",
            project_name="my-proj",
            project_id="p-123",
            bridge_key=b"\x00" * 32,
        )

        runner = CliRunner()
        result = runner.invoke(
            cli,
            ["--api-key", "global-key", "connect", "--project", "my-proj"],
        )
        assert result.exit_code == 0

        mock_opik_cls.assert_called_once_with(
            project_name="my-proj",
            api_key="global-key",
            workspace=None,
            _show_misconfiguration_message=False,
        )
