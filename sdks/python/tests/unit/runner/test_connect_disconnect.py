import errno
from unittest.mock import MagicMock, patch

import httpx
import pytest
from click.testing import CliRunner

from opik.cli.main import cli
from opik.runner import state


@pytest.fixture
def cli_runner():
    return CliRunner()


class TestConnect:
    @patch("opik.cli.connect.runner_loop")
    @patch("opik.cli.connect.OpikApi")
    @patch("opik.cli.connect.httpx_client")
    @patch("opik.cli.connect.OpikConfig")
    def test_connect__with_name_flag__registers_and_starts_loop(
        self, mock_config_cls, mock_httpx_client, mock_api_cls, mock_loop, cli_runner
    ):
        config = MagicMock()
        config.url_override = "https://api.test"
        config.api_key = "key-123"
        config.workspace = "ws"
        mock_config_cls.return_value = config

        mock_httpx_client.get.return_value = MagicMock()

        api = MagicMock()
        api.runners.connect_runner.return_value = {"runner_id": "r-abc"}
        mock_api_cls.return_value = api

        mock_loop.RunnerLoop.return_value.run = MagicMock()

        result = cli_runner.invoke(cli, ["connect", "--name", "my-runner"])
        assert result.exit_code == 0
        assert "r-abc" in result.output
        api.runners.connect_runner.assert_called_once()
        mock_loop.RunnerLoop.return_value.run.assert_called_once()

    @patch("opik.cli.connect.runner_loop")
    @patch("opik.cli.connect.OpikApi")
    @patch("opik.cli.connect.httpx_client")
    @patch("opik.cli.connect.OpikConfig")
    def test_connect__with_pair_code__sends_pairing_code(
        self, mock_config_cls, mock_httpx_client, mock_api_cls, mock_loop, cli_runner
    ):
        config = MagicMock()
        config.url_override = "https://api.test"
        config.api_key = "key"
        config.workspace = "ws"
        mock_config_cls.return_value = config

        mock_httpx_client.get.return_value = MagicMock()

        api = MagicMock()
        api.runners.connect_runner.return_value = {"runner_id": "r-xyz"}
        mock_api_cls.return_value = api
        mock_loop.RunnerLoop.return_value.run = MagicMock()

        result = cli_runner.invoke(cli, ["connect", "--pair", "ABCD"])
        assert result.exit_code == 0
        call_kwargs = api.runners.connect_runner.call_args[1]
        assert call_kwargs["pairing_code"] == "ABCD"

    @patch("opik.cli.connect._is_process_alive", return_value=True)
    def test_connect__runner_already_active__rejects_with_error(
        self, mock_alive, cli_runner
    ):
        runner_state = state.RunnerState(
            runner_id="r-1", pid=99999, name="r", base_url="http://x"
        )
        state.save_runner_state(runner_state)

        result = cli_runner.invoke(cli, ["connect"])
        assert result.exit_code != 0
        assert "already running" in result.output.lower()

    @patch("opik.cli.connect.runner_loop")
    @patch("opik.cli.connect.OpikApi")
    @patch("opik.cli.connect.httpx_client")
    @patch("opik.cli.connect.OpikConfig")
    def test_connect__stale_state_from_dead_process__clears_and_connects(
        self, mock_config_cls, mock_httpx_client, mock_api_cls, mock_loop, cli_runner
    ):
        runner_state = state.RunnerState(
            runner_id="r-old", pid=1, name="r", base_url="http://x"
        )
        state.save_runner_state(runner_state)

        config = MagicMock()
        config.url_override = "https://api.test"
        config.api_key = "key"
        config.workspace = "ws"
        mock_config_cls.return_value = config

        mock_httpx_client.get.return_value = MagicMock()

        api = MagicMock()
        api.runners.connect_runner.return_value = {"runner_id": "r-new"}
        mock_api_cls.return_value = api
        mock_loop.RunnerLoop.return_value.run = MagicMock()

        with patch("opik.cli.connect._is_process_alive", return_value=False):
            result = cli_runner.invoke(cli, ["connect"])
        assert result.exit_code == 0

    @patch("opik.cli.connect.OpikApi")
    @patch("opik.cli.connect.httpx_client")
    @patch("opik.cli.connect.OpikConfig")
    def test_connect__network_failure__shows_clean_error(
        self, mock_config_cls, mock_httpx_client, mock_api_cls, cli_runner
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

        result = cli_runner.invoke(cli, ["connect"])
        assert result.exit_code != 0
        assert "Could not connect to Opik at https://api.test" in result.output


class TestDisconnect:
    def test_disconnect__no_active_runner__prints_no_runner(self, cli_runner):
        result = cli_runner.invoke(cli, ["disconnect"])
        assert result.exit_code == 0
        assert "no runner" in result.output.lower()

    @patch("opik.runner.state.send_shutdown_signal")
    def test_disconnect__active_runner__sends_signal_and_clears_state(
        self, mock_signal, cli_runner
    ):
        runner_state = state.RunnerState(
            runner_id="r-1", pid=12345, name="r", base_url="http://x"
        )
        state.save_runner_state(runner_state)

        result = cli_runner.invoke(cli, ["disconnect"])
        assert result.exit_code == 0
        mock_signal.assert_called_once_with(12345)
        assert state.load_runner_state() is None

    @patch(
        "opik.runner.state.send_shutdown_signal",
        side_effect=OSError(errno.ESRCH, "No such process"),
    )
    def test_disconnect__dead_process__clears_state_with_message(
        self, mock_signal, cli_runner
    ):
        runner_state = state.RunnerState(
            runner_id="r-1", pid=99999, name="r", base_url="http://x"
        )
        state.save_runner_state(runner_state)

        result = cli_runner.invoke(cli, ["disconnect"])
        assert result.exit_code == 0
        assert "no longer running" in result.output.lower()
        assert state.load_runner_state() is None

    @patch(
        "opik.runner.state.send_shutdown_signal",
        side_effect=OSError(errno.EPERM, "Operation not permitted"),
    )
    def test_disconnect__permission_error__preserves_state(
        self, mock_signal, cli_runner
    ):
        runner_state = state.RunnerState(
            runner_id="r-1", pid=99999, name="r", base_url="http://x"
        )
        state.save_runner_state(runner_state)

        result = cli_runner.invoke(cli, ["disconnect"])
        assert result.exit_code == 0
        assert "could not signal" in result.output.lower()
        assert state.load_runner_state() is not None
