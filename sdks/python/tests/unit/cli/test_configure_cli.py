"""Tests for the ``opik configure`` command group."""

import pathlib

import click
import pytest
from unittest import mock

from click.testing import CliRunner

from opik.cli import cli
from opik.cli import assistants
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
            configure_cli._ask_about_mcp(["Claude Code"])

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


def test_configure_no_terminal__assumes_the_defaults_instead_of_demanding_yes():
    """No terminal means nobody to ask, and every question has a sane default.

    Requiring `-y` to say "yes, the defaults" was a step that existed only to be
    discovered, and the error teaching it was where an agent was most likely to
    stop.
    """
    runner = CliRunner()
    with (
        mock.patch.object(
            configure_cli.interactive_helpers, "is_interactive", return_value=False
        ),
        mock.patch.object(configure_cli, "run_interactive_configure") as spy,
    ):
        result = runner.invoke(cli, ["configure"])

    assert result.exit_code == 0
    assert spy.call_args.kwargs["automatic_approvals"] is True


def test_configure_with_a_terminal__keeps_its_questions():
    """A person in a shell keeps their prompts; only the no-tty case assumes."""
    runner = CliRunner()
    with (
        mock.patch.object(
            configure_cli.interactive_helpers, "is_interactive", return_value=True
        ),
        mock.patch.object(configure_cli, "run_interactive_configure") as spy,
    ):
        result = runner.invoke(cli, ["configure"])

    assert result.exit_code == 0
    assert spy.call_args.kwargs["automatic_approvals"] is False


class TestAgentDiscoverability:
    """An agent has to *find* the right invocation, not just be capable of it.

    Every dead end on this path was a silent one: `-y` succeeded, printed
    "configuration completed successfully", and wrote nothing to the AI client —
    so an agent asked for both reported done having delivered half.
    """

    def test_yes_alone__announces_that_the_assistant_step_was_skipped(self, capsys):
        with (
            mock.patch.object(
                configure_cli.interactive_helpers, "is_interactive", return_value=False
            ),
            mock.patch.object(configure_cli.assistants, "setup") as setup,
        ):
            configure_cli._setup_assistants({}, None, None, True)

        out = capsys.readouterr().out
        setup.assert_not_called()
        assert "Skipped AI client setup" in out
        assert "--install-mcp" in out, "must name the flag that includes it"

    def test_with_a_terminal__also_says_so(self, capsys):
        """`-y` reads as yes-to-everything, so a person is surprised too.

        They chose "stop asking me questions", not "skip my editor" — the same
        gap an agent hits, so the line is worth showing in both modes.
        """
        with (
            mock.patch.object(
                configure_cli.interactive_helpers, "is_interactive", return_value=True
            ),
            mock.patch.object(configure_cli.assistants, "setup") as setup,
        ):
            configure_cli._setup_assistants({}, None, None, True)

        setup.assert_not_called()
        assert "Skipped AI client setup" in capsys.readouterr().out

    def test_help_states_the_non_interactive_recipe(self):
        """Agents read --help before guessing."""
        result = CliRunner().invoke(cli, ["configure", "--help"])

        assert "--install-mcp" in result.output
        assert "-y" in result.output


class TestCometCloudHostMatch:
    """Deployment inference must match the host, not a substring of the URL.

    CodeQL flagged the first version (`py/incomplete-url-substring-sanitization`,
    high): `endswith("comet.com")` also accepts `evil-comet.com`, so a self-hosted
    URL could be classified as Opik Cloud and configured against the wrong
    deployment.
    """

    @pytest.mark.parametrize(
        "url",
        [
            "https://www.comet.com/opik/api",
            "https://comet.com/opik/api",
            "https://staging.comet.com/opik/api",
            "HTTPS://WWW.COMET.COM/opik/api",
        ],
    )
    def test_real_comet_hosts__match(self, url):
        assert configure_cli._is_comet_cloud_host(url) is True

    @pytest.mark.parametrize(
        "url",
        [
            "https://evil-comet.com/opik/api",  # suffix without a label boundary
            "https://notcomet.com/api",
            "https://comet.com.evil.net/api",  # comet.com as a left-hand label
            "https://attacker.com/?redirect=comet.com",  # only in the query
            "https://attacker.com/comet.com/api",  # only in the path
            "http://localhost:5173/api",
            "https://opik.acme.internal/opik/api",
        ],
    )
    def test_lookalikes_and_others__do_not_match(self, url):
        assert configure_cli._is_comet_cloud_host(url) is False

    def test_garbage_url__does_not_raise(self):
        assert configure_cli._is_comet_cloud_host("not a url at all") is False


class TestAssistantOutcomeReachesTheCaller:
    """The assistant step's result has to travel back up to the click command.

    The configurator takes the step as a callback and discards its return value,
    so the outcome comes back through a recorder. Nothing else notices when that
    wiring breaks — the flow still works, the analytics just quietly report that
    nothing was installed.
    """

    def test_outcome_from_the_step__is_returned(self):
        installed = assistants.Outcome(clients=2, skills=True)

        with (
            mock.patch.object(
                configure_cli, "_setup_assistants", return_value=installed
            ),
            mock.patch.object(configure_cli.opik_configure, "OpikConfigurator") as ctor,
        ):
            # The configurator calls the step it was handed, the way the real one does.
            ctor.side_effect = lambda **kwargs: mock.Mock(
                configure=lambda: kwargs["assistant_setup"]({}, True, True, False)
            )

            assert configure_cli.run_interactive_configure(use_local=True) == installed

    def test_step_never_ran__reports_nothing_done(self):
        """A configurator that never calls the step must not look like a success."""
        with mock.patch.object(
            configure_cli.opik_configure, "OpikConfigurator"
        ) as ctor:
            ctor.return_value = mock.Mock(configure=lambda: None)

            outcome = configure_cli.run_interactive_configure(use_local=True)

        assert outcome == assistants.NOTHING_DONE
        assert outcome.clients == 0
