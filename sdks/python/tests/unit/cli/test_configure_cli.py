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
    friends. Configuring Opik is not consent for that. The client picker is that
    question — one question, the same one `opik mcp configure` asks — preceded by
    a block saying what the step is and what it writes.
    """

    @staticmethod
    def _run(
        install_mcp=None,
        install_skills=None,
        auto=False,
        declined=False,
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
                configure_cli.click, "confirm", return_value=True
            ) as confirm,
            mock.patch.object(
                configure_cli.assistants,
                "setup",
                side_effect=lambda *a, **k: (
                    setup_calls.append(k),
                    assistants.Outcome(
                        clients=0 if declined else 1,
                        skills=True,
                        mcp_declined=declined,
                    ),
                )[1],
            ),
        ):
            outcome = configure_cli._setup_assistants(
                {}, install_mcp, install_skills, auto
            )
        return confirm, setup_calls, outcome

    def test_no_flags__reaches_the_installer_without_a_second_question(self):
        """The picker is the question; a yes/no in front of it asked twice."""
        confirm, setup_calls, _ = self._run()

        assert not confirm.called
        assert len(setup_calls) == 1
        assert setup_calls[0]["install_mcp"] is True

    def test_no_flags__picker_is_not_pre_confirmed(self):
        """`assume_confirmed` would skip the picker, leaving nothing to answer."""
        _, setup_calls, _ = self._run()

        assert setup_calls[0]["assume_confirmed"] is False

    def test_declining_in_the_picker__is_reported_as_declined(self):
        _, _, outcome = self._run(declined=True)

        assert outcome.mcp_decision == "declined"

    def test_no_flags__still_offers_the_skill_pack(self):
        """The pack is a separate question: it needs no MCP server."""
        _, setup_calls, _ = self._run(declined=True)

        assert setup_calls[0]["skills"].decision is configure_cli.consent.Decision.ASK

    def test_install_mcp_flag__is_the_consent__skips_the_picker(self):
        _, setup_calls, _ = self._run(install_mcp=True)

        assert setup_calls[0]["assume_confirmed"] is True

    def test_no_terminal__does_not_prompt(self):
        confirm, setup_calls, _ = self._run(interactive=False)

        assert not confirm.called
        assert setup_calls == []

    def test_no_host_detected__nothing_worth_asking(self):
        confirm, setup_calls, _ = self._run(detected=())

        assert not confirm.called
        assert setup_calls == []

    def test_intro_does_not_list_the_clients(self, capsys):
        """The picker directly below is that list; twice pushed the question off."""
        self._run(detected=("Claude Code", "Codex", "Cursor"))

        out = capsys.readouterr().out
        assert "Found:" not in out
        assert "Claude Code" not in out


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
            side_effect=lambda *a, **k: (
                calls.append(k),
                assistants.Outcome(clients=1, skills=True),
            )[1],
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


class TestIdentityIsReportedWithBothEvents:
    """`opik configure` reports before and after it writes the configuration.

    The account is resolved separately for each, because a first-ever run has no
    credential to resolve until the flow has written one — and that run is exactly
    the one the funnel starts from.
    """

    def test_entry_and_result__each_resolve_the_account(self):
        runner = CliRunner()
        answers = [
            {"identity_lookup": "no_credential"},
            {"user_id": "someone", "identity_lookup": "resolved"},
        ]

        with (
            mock.patch.object(
                configure_cli.interactive_helpers, "is_interactive", return_value=True
            ),
            mock.patch.object(configure_cli, "run_interactive_configure"),
            mock.patch.object(
                configure_cli.account_identity,
                "event_properties",
                side_effect=answers,
            ),
            mock.patch.object(configure_cli.analytics, "track_event") as track,
        ):
            result = runner.invoke(cli, ["configure", "--use-local"])

        assert result.exit_code == 0
        entry, outcome = track.call_args_list
        assert entry.kwargs["identity_lookup"] == "no_credential"
        assert "user_id" not in entry.kwargs
        assert outcome.kwargs["user_id"] == "someone"
        # Asserted alongside the login: without it the pair still passes when the
        # metadata that says how to read the login goes missing or changes value.
        assert outcome.kwargs["identity_lookup"] == "resolved"


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


class TestTheDecisionIsReported:
    """Why the AI client step ended the way it did, as one analytics value.

    Every one of these cases used to report an identical `clients_written = 0`,
    so a machine with no AI client on it, a bare `-y`, and a user who actually
    said no were the same row in the funnel — and the obvious reading of that
    row ("they refused") was the one case that could not be told apart from the
    other three.
    """

    @staticmethod
    def _reason(
        install_mcp=None, auto=False, declined=False, interactive=True, detected=1
    ):
        with (
            mock.patch.object(
                configure_cli.mcp_installer,
                "detected_host_names",
                return_value=["Cursor"] * detected,
            ),
            mock.patch.object(
                configure_cli.interactive_helpers,
                "is_interactive",
                return_value=interactive,
            ),
            mock.patch.object(
                configure_cli.assistants,
                "setup",
                return_value=assistants.Outcome(
                    clients=0 if declined else 1,
                    skills=True,
                    mcp_declined=declined,
                ),
            ),
        ):
            return configure_cli._setup_assistants({}, install_mcp, None, auto)

    def test_nothing_detected__is_not_a_refusal(self):
        outcome = self._reason(detected=0)

        assert outcome.mcp_decision == "nothing_detected"
        assert outcome.detected == 0

    def test_declined__says_declined(self):
        """Declining now happens in the picker, which is the only question."""
        outcome = self._reason(declined=True)

        assert outcome.mcp_decision == "declined"
        assert outcome.detected == 1

    def test_accepted__says_requested(self):
        outcome = self._reason(declined=False)

        assert outcome.mcp_decision == "requested"

    def test_assume_yes__is_not_a_refusal(self):
        outcome = self._reason(auto=True)

        assert outcome.mcp_decision == "assume_yes"

    def test_no_terminal__is_not_a_refusal(self):
        outcome = self._reason(interactive=False)

        assert outcome.mcp_decision == "no_terminal"

    def test_flag__says_requested_without_asking(self):
        outcome = self._reason(install_mcp=True, interactive=False)

        assert outcome.mcp_decision == "requested"


class TestResultEventCarriesTheFunnelProperties:
    @staticmethod
    def _result_event(outcome):
        runner = CliRunner()
        with (
            mock.patch.object(
                configure_cli.interactive_helpers, "is_interactive", return_value=True
            ),
            mock.patch.object(
                configure_cli, "run_interactive_configure", return_value=outcome
            ),
            mock.patch.object(
                configure_cli.account_identity, "event_properties", return_value={}
            ),
            mock.patch.object(configure_cli.analytics, "track_event") as track,
        ):
            assert runner.invoke(cli, ["configure", "--use-local"]).exit_code == 0
        return track.call_args_list[-1].kwargs

    def test_zero_clients__reports_why(self):
        event = self._result_event(
            assistants.Outcome(
                clients=0, skills=False, detected=0, mcp_decision="nothing_detected"
            )
        )

        assert event["clients_written"] == 0
        assert event["detected_clients"] == 0
        assert event["mcp_decision"] == "nothing_detected"

    def test_registered__reports_verification_and_failures(self):
        event = self._result_event(
            assistants.Outcome(
                clients=1,
                skills=True,
                failed_clients=2,
                verified=False,
                detected=3,
                mcp_decision="requested",
            )
        )

        assert event["clients_failed"] == 2
        # A client written into is not a client that works; the funnel counted
        # both as success until this was reported.
        assert event["verification_succeeded"] is False

    def test_interactive__is_on_the_result_too(self):
        """Step 1 filtered on it and step 2 could not, so the funnel compared
        two different populations."""
        event = self._result_event(assistants.Outcome(clients=1, skills=True))

        assert event["interactive"] is True


class TestFailedRunsAreReported:
    """A run that raises reported nothing at all, so its entry event had no
    sibling and the drop looked the same as a user who walked away."""

    @staticmethod
    def _invoke(exception):
        runner = CliRunner()
        with (
            mock.patch.object(
                configure_cli.interactive_helpers, "is_interactive", return_value=True
            ),
            mock.patch.object(
                configure_cli, "run_interactive_configure", side_effect=exception
            ),
            mock.patch.object(
                configure_cli.account_identity, "event_properties", return_value={}
            ),
            mock.patch.object(configure_cli.analytics, "track_event") as track,
        ):
            result = runner.invoke(cli, ["configure", "--use-local"])
        return result, track.call_args_list

    def test_exception__emits_a_failure_event(self):
        _, calls = self._invoke(ConnectionError("no route to host"))

        assert calls[-1].args == ("configuration", "configure", "failed")
        assert calls[-1].kwargs["error_type"] == "ConnectionError"

    def test_failure_event__does_not_carry_the_message(self):
        """An exception message can hold a URL, a workspace or a key."""
        _, calls = self._invoke(ConnectionError("https://secret.internal/opik"))

        assert "secret.internal" not in repr(calls[-1].kwargs)

    def test_abort__is_reported_and_still_aborts(self):
        """Ctrl-C at a prompt is the most common way this ends early."""
        result, calls = self._invoke(click.Abort())

        assert calls[-1].kwargs["error_type"] == "Abort"
        assert result.exit_code != 0


class TestBothDecisionsReachTheEvent:
    """Every user's accept/decline, for both halves, in one vocabulary.

    The two questions are asked in different places — the server by the consent
    layer, the pack by the installer step — so it is easy for one of them to
    stop being reported without anything else noticing.
    """

    @staticmethod
    def _result_event(outcome):
        runner = CliRunner()
        with (
            mock.patch.object(
                configure_cli.interactive_helpers, "is_interactive", return_value=True
            ),
            mock.patch.object(
                configure_cli, "run_interactive_configure", return_value=outcome
            ),
            mock.patch.object(
                configure_cli.account_identity, "event_properties", return_value={}
            ),
            mock.patch.object(configure_cli.analytics, "track_event") as track,
        ):
            assert runner.invoke(cli, ["configure", "--use-local"]).exit_code == 0
        return track.call_args_list[-1].kwargs

    def test_both_decisions_are_reported(self):
        event = self._result_event(
            assistants.Outcome(
                clients=0,
                skills=True,
                mcp_decision="declined",
                skills_decision="requested",
            )
        )

        assert event["mcp_decision"] == "declined"
        assert event["skills_decision"] == "requested"

    def test_declining_the_server__does_not_imply_declining_the_pack(self):
        """They used to be coupled, so one Enter answered both."""
        _, setup_calls, _ = TestAssistantConfirmation._run(declined=True)

        assert setup_calls[0]["skills"].decision is configure_cli.consent.Decision.ASK


class TestTheMcpQuestionIsRecommended:
    def test_headline_names_opik_mcp_and_recommends_it(self, capsys):
        with (
            mock.patch.object(
                configure_cli.mcp_installer,
                "detected_host_names",
                return_value=["Cursor"],
            ),
            mock.patch.object(configure_cli.click, "confirm", return_value=False),
        ):
            configure_cli._ask_about_mcp()

        out = capsys.readouterr().out
        assert "Set up Opik MCP for your AI client?" in out
        assert "(Recommended)" in out

    def test_the_intro_asks_nothing_itself(self):
        """It is the case for the step; the picker after it is the question."""
        with mock.patch.object(configure_cli.click, "confirm") as confirm:
            assert configure_cli._ask_about_mcp() is True

        assert not confirm.called


class TestTheDeploymentQuestionTakesBothInputs:
    """Arrow keys for people, the number for everyone who already knows it.

    This was an `input()` prompt reading `1`, `2`, `3` or Enter, so anything
    driving the CLI through a pty — and anyone's muscle memory — sends a digit
    and Enter. A picker that only understood arrows would break both.
    """

    @staticmethod
    def _pick(keys):
        from opik.cli import selector

        pressed = iter(keys)
        with (
            mock.patch.object(selector, "is_supported", return_value=True),
            mock.patch.object(
                selector, "_key_reader", return_value=lambda: next(pressed)
            ),
        ):
            return configure_cli._ask_for_deployment_type()

    def test_typing_the_number__picks_that_row(self):
        from opik.cli import selector

        result = self._pick(["3", selector.ACCEPT])

        assert result is configure_cli.interactive_helpers.DeploymentType.LOCAL

    def test_enter_alone__takes_the_default(self):
        from opik.cli import selector

        result = self._pick([selector.ACCEPT])

        assert result is configure_cli.interactive_helpers.DeploymentType.CLOUD

    def test_arrow_keys__move_the_cursor(self):
        from opik.cli import selector

        result = self._pick([selector.DOWN, selector.ACCEPT])

        assert result is configure_cli.interactive_helpers.DeploymentType.SELF_HOSTED

    def test_a_number_out_of_range__is_ignored(self):
        from opik.cli import selector

        result = self._pick(["9", selector.ACCEPT])

        assert result is configure_cli.interactive_helpers.DeploymentType.CLOUD

    def test_cancelling__aborts_rather_than_re_asking(self):
        """Ctrl-C ended the command at the prompt this replaces."""
        from opik.cli import selector

        with pytest.raises(click.Abort):
            self._pick([selector.CANCEL])

    def test_no_picker_support__falls_back_to_the_original_prompt(self):
        from opik.cli import selector

        with (
            mock.patch.object(selector, "is_supported", return_value=False),
            mock.patch("builtins.input", return_value="2") as typed,
        ):
            result = configure_cli._ask_for_deployment_type()

        assert typed.called, "the plain prompt is what a pipe or CI log gets"
        assert result is configure_cli.interactive_helpers.DeploymentType.SELF_HOSTED

    def test_no_picker_support__enter_still_means_the_default(self):
        from opik.cli import selector

        with (
            mock.patch.object(selector, "is_supported", return_value=False),
            mock.patch("builtins.input", return_value=""),
        ):
            result = configure_cli._ask_for_deployment_type()

        assert result is configure_cli.interactive_helpers.DeploymentType.CLOUD
