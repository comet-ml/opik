"""Tests for the ``opik configure`` command group."""

import pathlib

import click
import pytest
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
    # CliRunner detaches stdin, and the command now refuses without `-y` there,
    # so this asserts the dispatch rather than the new no-terminal guard.
    with (
        mock.patch.object(
            configure_cli.interactive_helpers, "is_interactive", return_value=True
        ),
        mock.patch.object(configure_cli, "run_interactive_configure") as spy,
    ):
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


class TestCodingAgentFlow:
    """`opik configure` driven by a coding agent asked to "set Opik up".

    The agent has no tty — `is_interactive()` is False in its shell, exactly as in
    CI — so the whole flow used to abort on the deployment-type prompt, which
    `-y` does not answer. What separates the two callers is that the agent can
    name flags and CI names none.
    """

    @staticmethod
    def _deployment(env, interactive=False):
        with (
            mock.patch.object(
                configure_cli.interactive_helpers,
                "is_interactive",
                return_value=interactive,
            ),
            mock.patch.dict(configure_cli.os.environ, env, clear=True),
        ):
            return configure_cli._deployment_type()

    def test_api_key_only__is_cloud(self):
        result = self._deployment({"OPIK_API_KEY": "k"})

        assert result is configure_cli.interactive_helpers.DeploymentType.CLOUD

    def test_localhost_url__is_local(self):
        result = self._deployment({"OPIK_URL_OVERRIDE": "http://localhost:5173/api"})

        assert result is configure_cli.interactive_helpers.DeploymentType.LOCAL

    def test_comet_url__is_cloud(self):
        result = self._deployment(
            {"OPIK_URL_OVERRIDE": "https://www.comet.com/opik/api", "OPIK_API_KEY": "k"}
        )

        assert result is configure_cli.interactive_helpers.DeploymentType.CLOUD

    def test_self_hosted_comet_path__is_self_hosted(self):
        result = self._deployment(
            {"OPIK_URL_OVERRIDE": "https://opik.acme.internal/opik/api"}
        )

        assert result is configure_cli.interactive_helpers.DeploymentType.SELF_HOSTED

    def test_nothing_set__errors_naming_what_to_provide(self):
        """A dead end for an agent unless the message says how to fix it."""
        with pytest.raises(click.ClickException) as excinfo:
            self._deployment({})

        message = str(excinfo.value)
        assert "OPIK_API_KEY" in message
        assert "--use_local" in message

    def test_with_a_terminal__still_asks(self):
        """The interactive path is untouched; inference is the no-tty fallback."""
        with (
            mock.patch.object(
                configure_cli.interactive_helpers, "is_interactive", return_value=True
            ),
            mock.patch.object(
                configure_cli.interactive_helpers, "ask_user_for_deployment_type"
            ) as ask,
        ):
            configure_cli._deployment_type()

        ask.assert_called_once()

    def test_install_mcp_flag__is_the_consent_that_reaches_the_installer(self):
        """`opik configure --install-mcp` names no client, so the flag must carry it."""
        calls = []
        with mock.patch.object(
            configure_cli.assistants,
            "setup",
            side_effect=lambda *a, **k: calls.append(k),
        ):
            configure_cli._setup_assistants({}, True, None, True)

        assert calls and calls[0]["assume_confirmed"] is True

    def test_no_flag_and_no_terminal__does_not_reach_the_installer(self):
        """The CI case: nothing was asked for, so nothing is written."""
        calls = []
        with (
            mock.patch.object(
                configure_cli.mcp_installer,
                "detected_host_names",
                return_value=["Cursor"],
            ),
            mock.patch.object(
                configure_cli.interactive_helpers, "is_interactive", return_value=False
            ),
            mock.patch.object(
                configure_cli.assistants,
                "setup",
                side_effect=lambda *a, **k: calls.append(k),
            ),
        ):
            configure_cli._setup_assistants({}, None, None, False)

        assert calls == []


def test_configure_no_terminal_without_yes__names_the_flag_to_add():
    """A bare `Aborted!` is a dead end for a coding agent; this is not."""
    runner = CliRunner()
    with (
        mock.patch.object(
            configure_cli.interactive_helpers, "is_interactive", return_value=False
        ),
        mock.patch.object(configure_cli, "run_interactive_configure") as spy,
    ):
        result = runner.invoke(cli, ["configure"])

    assert result.exit_code != 0
    assert "-y" in result.output
    spy.assert_not_called()


def test_configure_no_terminal_with_yes__proceeds():
    runner = CliRunner()
    with (
        mock.patch.object(
            configure_cli.interactive_helpers, "is_interactive", return_value=False
        ),
        mock.patch.object(configure_cli, "run_interactive_configure") as spy,
    ):
        result = runner.invoke(cli, ["configure", "-y"])

    assert result.exit_code == 0
    spy.assert_called_once()
