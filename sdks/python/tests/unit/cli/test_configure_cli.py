"""Tests for the ``opik configure`` command group."""

import pathlib
from unittest import mock

from click.testing import CliRunner

from opik.cli import cli
from opik.cli import configure as configure_cli


def test_configure_status__prints_config_summary():
    runner = CliRunner()
    config = mock.Mock(
        config_file_exists=True,
        config_file_fullpath=pathlib.Path("/home/u/.opik.config"),
        url_override="https://dev.comet.com/opik/api/",
        workspace="my-ws",
    )
    with mock.patch.object(
        configure_cli.opik_config, "OpikConfig", return_value=config
    ):
        result = runner.invoke(cli, ["configure", "status"])

    assert result.exit_code == 0
    assert "Your Opik configuration" in result.output
    assert "https://dev.comet.com/opik/api/" in result.output
    assert "my-ws" in result.output


def test_configure_status__not_configured__points_to_configure():
    runner = CliRunner()
    config = mock.Mock(
        config_file_exists=False,
        config_file_fullpath=pathlib.Path("/home/u/.opik.config"),
    )
    with mock.patch.object(
        configure_cli.opik_config, "OpikConfig", return_value=config
    ):
        result = runner.invoke(cli, ["configure", "status"])

    assert result.exit_code == 0
    assert "not found" in result.output
    assert "opik configure" in result.output


def test_configure_no_subcommand__runs_configurator():
    runner = CliRunner()
    with mock.patch.object(configure_cli, "run_interactive_configure") as spy:
        result = runner.invoke(cli, ["configure", "--use-local"])

    assert result.exit_code == 0
    spy.assert_called_once()
    assert spy.call_args.kwargs["use_local"] is True


class TestAssistantConfirmation:
    """`opik configure` must ask before editing another tool's config.

    Registering an MCP server writes into files owned by Claude Code, Cursor and
    friends. Configuring Opik is not consent for that, and dropping straight into
    the host picker left no way to answer "no, just configure Opik".
    """

    @staticmethod
    def _run(
        install_mcp=None,
        install_skills=None,
        auto=False,
        answer=True,
        interactive=True,
        detected=("Claude Code", "Cursor"),
    ):
        setup_calls = []
        with (
            mock.patch.object(
                configure_cli.mcp_installer,
                "detected_host_names",
                return_value=list(detected),
            ),
            mock.patch.object(
                configure_cli.interactive_helpers,
                "is_interactive",
                return_value=interactive,
            ),
            mock.patch.object(
                configure_cli.click, "confirm", return_value=answer
            ) as confirm,
            mock.patch.object(
                configure_cli.assistants,
                "setup",
                side_effect=lambda *a, **k: setup_calls.append(k),
            ),
        ):
            configure_cli._setup_assistants({}, install_mcp, install_skills, auto)
        return confirm, setup_calls

    def test_no_flags__asks_and_proceeds_on_yes(self):
        confirm, setup_calls = self._run(answer=True)

        assert confirm.called
        assert len(setup_calls) == 1

    def test_no_flags__declined__installs_nothing(self):
        confirm, setup_calls = self._run(answer=False)

        assert confirm.called
        assert setup_calls == []

    def test_install_mcp_flag__is_the_consent__does_not_ask(self):
        confirm, setup_calls = self._run(install_mcp=True, answer=False)

        assert not confirm.called
        assert len(setup_calls) == 1

    def test_defaults_to_no(self):
        with (
            mock.patch.object(
                configure_cli.mcp_installer,
                "detected_host_names",
                return_value=["Claude Code"],
            ),
            mock.patch.object(
                configure_cli.interactive_helpers, "is_interactive", return_value=True
            ),
            mock.patch.object(configure_cli.click, "confirm") as confirm,
        ):
            confirm.return_value = False
            configure_cli._confirm_assistant_step()

        assert confirm.call_args.kwargs["default"] is False

    def test_no_terminal__does_not_prompt(self):
        confirm, setup_calls = self._run(interactive=False)

        assert not confirm.called
        assert setup_calls == []

    def test_no_host_detected__nothing_worth_asking(self):
        confirm, setup_calls = self._run(detected=())

        assert not confirm.called
        assert setup_calls == []

    def test_prompt_names_the_hosts_it_found(self, capsys):
        self._run(detected=("Claude Code", "Cursor", "Codex"))

        assert "Claude Code, Cursor and Codex" in capsys.readouterr().out
