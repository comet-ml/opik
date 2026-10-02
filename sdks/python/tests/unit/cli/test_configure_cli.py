"""Tests for the ``opik configure`` command group."""

import pathlib
from types import SimpleNamespace

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
    """`opik configure` asks before editing another tool's config; a terminal
    "yes" redirects to `opik mcp configure`, flags and unattended runs stay inline."""

    @staticmethod
    def _run(
        install_mcp=None,
        install_skills=None,
        auto=False,
        answer=True,
        declined=False,
        interactive=True,
        detected=("Claude Code", "Cursor"),
    ):
        setup_calls = []
        with (
            mock.patch.object(
                configure_cli.mcp_installer,
                "detected_host_keys",
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
            outcome, redirected = configure_cli._setup_assistants(
                {}, install_mcp, install_skills, auto
            )
        return confirm, setup_calls, outcome, redirected

    def test_no_flags__asks_permission_then_redirects_to_the_mcp_flow(self):
        """One MCP setup flow, not two: the yes buys `opik mcp configure`."""
        confirm, setup_calls, _, redirected = self._run(answer=True)

        assert confirm.called
        assert redirected is True
        assert setup_calls == [], "the inline installer must not also run"

    def test_no_flags__permission_refused__does_not_register_or_redirect(self):
        """With no pack question left, a "no" to the server runs nothing."""
        confirm, setup_calls, outcome, redirected = self._run(answer=False)

        assert confirm.called
        assert redirected is False
        assert setup_calls == []
        assert outcome.mcp_decision == "declined"

    def test_skipping_the_picker__is_not_reported_as_declining_permission(self):
        """The two are different answers and the funnel needs both.

        Folding a skipped picker into `mcp_decision` relabelled those runs as
        never having accepted, which hid the one drop the funnel exists to
        show: said yes, then chose no client.
        """
        _, _, outcome, _ = self._run(answer=True, declined=True, install_mcp=True)

        assert outcome.mcp_decision == "requested", "they did give permission"
        assert outcome.clients == 0, "and still registered nothing"
        assert outcome.mcp_declined is True, "deliberately, not a failure"

    def test_declining_the_server__skips_the_pack_too(self):
        """Refusing the server refuses the pack, which also writes into AI
        clients; `--install-skills` still overrides."""
        _, setup_calls, outcome, _ = self._run(answer=False, install_skills=None)

        assert setup_calls == []
        assert outcome.skills_decision == "declined"

    def test_install_mcp_flag__is_the_consent__skips_the_picker(self):
        """A script's flag registers inline rather than ending inside an agent."""
        _, setup_calls, _, redirected = self._run(install_mcp=True)

        assert redirected is False
        assert setup_calls[0]["assume_confirmed"] is True

    def test_no_terminal__does_not_prompt(self):
        confirm, setup_calls, _, redirected = self._run(interactive=False)

        assert not confirm.called
        assert setup_calls == []
        assert redirected is False

    def test_no_host_detected__nothing_worth_asking(self):
        confirm, setup_calls, _, redirected = self._run(detected=())

        assert not confirm.called
        assert setup_calls == []
        assert redirected is False

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
                "detected_host_keys",
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
                configure_cli, "_setup_assistants", return_value=(installed, False)
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
        install_mcp=None,
        auto=False,
        answer=True,
        declined=False,
        interactive=True,
        detected=1,
    ):
        with (
            mock.patch.object(
                configure_cli.mcp_installer,
                "detected_host_keys",
                return_value=["Cursor"] * detected,
            ),
            mock.patch.object(
                configure_cli.interactive_helpers,
                "is_interactive",
                return_value=interactive,
            ),
            mock.patch.object(configure_cli.click, "confirm", return_value=answer),
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
            outcome, _ = configure_cli._setup_assistants({}, install_mcp, None, auto)
            return outcome

    def test_nothing_detected__is_not_a_refusal(self):
        outcome = self._reason(detected=0)

        assert outcome.mcp_decision == "nothing_detected"
        assert outcome.detected == 0

    def test_declined__says_declined(self):
        """Refusing the permission question, before the picker is reached."""
        outcome = self._reason(answer=False)

        assert outcome.mcp_decision == "declined"
        assert outcome.detected == 1

    def test_accepted__says_requested(self):
        outcome = self._reason(answer=True, declined=False)

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

    def test_declining_the_server__reports_the_pack_as_declined_too(self):
        """One decision, one reason — still distinct from a failed download."""
        _, _, outcome, _ = TestAssistantConfirmation._run(answer=False)

        assert outcome.skills_decision == "declined"


class TestTheRedirectIntoTheMcpFlow:
    """A "yes" to MCP hands over to `opik mcp configure`, the one implementation."""

    @staticmethod
    def _run(redirect, outcome=None):
        runner = CliRunner()

        def flow(**kwargs):
            kwargs["progress"].redirect_to_mcp = redirect
            return outcome or assistants.Outcome(clients=1, skills=True)

        with (
            mock.patch.object(
                configure_cli.interactive_helpers, "is_interactive", return_value=True
            ),
            mock.patch.object(
                configure_cli, "run_interactive_configure", side_effect=flow
            ),
            mock.patch.object(
                configure_cli.account_identity, "event_properties", return_value={}
            ),
            mock.patch.object(configure_cli.analytics, "track_event") as track,
            mock.patch("opik.cli.mcp.run_configure") as run_configure,
        ):
            result = runner.invoke(cli, ["configure", "--use-local"])

        assert result.exit_code == 0, result.output
        return run_configure, track

    def test_asked_for__runs_the_mcp_flow(self):
        run_configure, _ = self._run(redirect=True)

        assert run_configure.called

    def test_not_asked_for__does_not(self):
        run_configure, _ = self._run(redirect=False)

        assert not run_configure.called

    def test_the_result_event_is_reported_first(self):
        """Reported before handing over: the MCP flow may replace this process."""
        run_configure, track = self._run(redirect=True)

        reported_before_redirect = track.call_args_list[-1].args[:3]
        assert reported_before_redirect == ("configuration", "configure", "result")
        assert run_configure.called

    def test_redirect__result_event_says_the_mcp_step_was_handed_over(self):
        """Otherwise a handover reads as `requested` with nothing written."""
        _, track = self._run(redirect=True)

        assert track.call_args_list[-1].kwargs["mcp_redirected"] is True

    def test_no_redirect__result_event_says_so(self):
        _, track = self._run(redirect=False)

        assert track.call_args_list[-1].kwargs["mcp_redirected"] is False

    def test_inline__result_event_carries_the_connection_signals(self):
        """The same signals as `opik mcp configure`'s result, for inline runs."""
        outcome = assistants.Outcome(
            clients=1,
            skills=True,
            transport="remote",
            sign_in="failed",
            stale_tool="removed",
        )

        _, track = self._run(redirect=False, outcome=outcome)

        event = track.call_args_list[-1].kwargs
        assert (event["transport"], event["sign_in"], event["cancelled"]) == (
            "remote",
            "failed",
            False,
        )
        assert event["stale_tool"] == "removed"


class TestTheMcpQuestionIsRecommended:
    def test_headline_names_opik_mcp_and_recommends_it(self, capsys):
        with (
            mock.patch.object(
                configure_cli.mcp_installer,
                "detected_host_keys",
                return_value=["Cursor"],
            ),
            mock.patch.object(configure_cli.click, "confirm", return_value=False),
        ):
            configure_cli._ask_about_mcp()

        out = capsys.readouterr().out
        # A statement, not a question: `click.confirm` below asks the question,
        # and this block used to ask it too.
        assert "Opik MCP" in out
        assert "?" not in out
        assert "(Recommended)" in out

    def test_permission_is_asked_with_a_real_label_and_defaults_to_yes(self):
        """The empty label it replaced made Enter a silent refusal."""
        with mock.patch.object(
            configure_cli.click, "confirm", return_value=True
        ) as confirm:
            assert configure_cli._ask_about_mcp() is True

        assert confirm.call_args.args[0].strip(), "not an empty label"
        assert confirm.call_args.kwargs["default"] is True


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
            return configure_cli.ask_for_deployment_type("Where?")

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
            result = configure_cli.ask_for_deployment_type("Where?")

        assert typed.called, "the plain prompt is what a pipe or CI log gets"
        assert result is configure_cli.interactive_helpers.DeploymentType.SELF_HOSTED

    def test_no_picker_support__enter_still_means_the_default(self):
        from opik.cli import selector

        with (
            mock.patch.object(selector, "is_supported", return_value=False),
            mock.patch("builtins.input", return_value=""),
        ):
            result = configure_cli.ask_for_deployment_type("Where?")

        assert result is configure_cli.interactive_helpers.DeploymentType.CLOUD


class TestTheJourneyIsReported:
    """Where the run got to, not only how it ended.

    The click command is the only frame that can report — `analytics` drops any
    event raised inside a frame that already reported one — so the flow cannot
    emit as it goes. `Progress` carries the milestones up instead, and survives
    the exception, which is what lets a failure say which question it died at.
    """

    @staticmethod
    def _events(*, raises=None, use_local=True):
        runner = CliRunner()
        outcome = assistants.Outcome(clients=1, skills=True)

        def fake_run(*a, progress=None, **k):
            if progress is not None:
                progress.stage = configure_cli.Progress.CREDENTIALS
                progress.deployment = "cloud"
            if raises is not None:
                raise raises
            if progress is not None:
                progress.stage = configure_cli.Progress.DONE
            return outcome

        with (
            mock.patch.object(
                configure_cli.interactive_helpers, "is_interactive", return_value=True
            ),
            mock.patch.object(
                configure_cli, "run_interactive_configure", side_effect=fake_run
            ),
            mock.patch.object(
                configure_cli.account_identity, "event_properties", return_value={}
            ),
            mock.patch.object(configure_cli.analytics, "track_event") as track,
        ):
            runner.invoke(cli, ["configure", "--use-local"])
        return track.call_args_list

    def test_result__carries_the_deployment_and_the_final_stage(self):
        event = self._events()[-1].kwargs

        assert event["deployment"] == "cloud"
        assert event["stage"] == "done"

    def test_failure__says_which_stage_it_died_at(self):
        calls = self._events(raises=ConnectionError("boom"))

        assert calls[-1].args == ("configuration", "configure", "failed")
        assert calls[-1].kwargs["stage"] == "credentials"
        assert calls[-1].kwargs["deployment"] == "cloud"

    def test_use_local__records_the_deployment_without_asking(self):
        """`--use_local` answers the question, so it is never put to the user."""
        progress = configure_cli.Progress()
        with (
            mock.patch.object(configure_cli.opik_configure, "OpikConfigurator"),
            mock.patch.object(configure_cli, "_deployment_type") as asked,
        ):
            configure_cli.run_interactive_configure(use_local=True, progress=progress)

        assert not asked.called
        assert progress.deployment == "local"
        assert progress.stage == configure_cli.Progress.DONE

    def test_stages_are_ordered_for_a_funnel(self):
        stages = [
            configure_cli.Progress.DEPLOYMENT,
            configure_cli.Progress.CREDENTIALS,
            configure_cli.Progress.ASSISTANTS,
            configure_cli.Progress.DONE,
        ]

        assert stages == ["deployment", "credentials", "assistants", "done"]


class TestWhichClientsAreReported:
    """Counts cannot say which AI clients people pick.

    `detected_clients` and `clients_written` are numbers, so the question "what
    is popular" had no answer. The keys ride along as sorted, comma-joined
    strings: one stable breakdown value, and `splitByChar` gets back to
    per-client counts.
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

    def test_reports_what_was_offered_and_what_was_taken(self):
        event = self._result_event(
            assistants.Outcome(
                clients=2,
                skills=True,
                detected_keys=("cursor", "claude-code", "codex"),
                registered_clients=("cursor", "claude-code"),
            )
        )

        assert event["clients_detected"] == "claude-code,codex,cursor"
        assert event["clients_registered"] == "claude-code,cursor"

    def test_sorted__so_the_same_set_is_one_breakdown_value(self):
        """Otherwise picker order splits one combination across several rows."""
        first = self._result_event(
            assistants.Outcome(
                clients=2, skills=True, registered_clients=("cursor", "codex")
            )
        )
        second = self._result_event(
            assistants.Outcome(
                clients=2, skills=True, registered_clients=("codex", "cursor")
            )
        )

        assert first["clients_registered"] == second["clients_registered"]

    def test_nothing_registered__is_empty_not_absent(self):
        event = self._result_event(assistants.Outcome(clients=0, skills=False))

        assert event["clients_registered"] == ""


class TestPickerSkippedSeparatesTheTwoRefusals:
    """There are two ways to end up writing to nothing, and they are not the same.

    Saying no to "Set up Opik MCP?" never reaches the client picker. Saying yes
    and then choosing nothing in it is a second, later refusal — and collapsing
    them into `mcp_decision` hid the step between them, which is the one the
    funnel exists to measure.
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

    def test_accepted_then_chose_no_client__stays_a_request(self):
        event = self._result_event(
            assistants.Outcome(
                clients=0, skills=False, mcp_decision="requested", mcp_declined=True
            )
        )

        assert event["mcp_decision"] == "requested"
        assert event["picker_skipped"] is True

    def test_refused_the_question__never_reached_the_picker(self):
        event = self._result_event(
            assistants.Outcome(clients=0, skills=False, mcp_decision="declined")
        )

        assert event["mcp_decision"] == "declined"
        assert event["picker_skipped"] is False

    def test_clients_registered__is_neither_kind_of_refusal(self):
        event = self._result_event(
            assistants.Outcome(
                clients=1,
                skills=True,
                mcp_decision="requested",
                registered_clients=("codex",),
            )
        )

        assert event["picker_skipped"] is False


class TestTheRedirectCarriesTheFlags:
    """Flags survive the handover; a lost refusal would install the pack anyway."""

    @staticmethod
    def _redirected(*args):
        from opik.cli import mcp as mcp_cli

        def flow(**kwargs):
            kwargs["progress"].redirect_to_mcp = True
            return assistants.Outcome(clients=0, skills=False)

        runner = CliRunner()
        with (
            mock.patch.object(
                configure_cli.interactive_helpers, "is_interactive", return_value=True
            ),
            mock.patch.object(
                configure_cli, "run_interactive_configure", side_effect=flow
            ),
            mock.patch.object(configure_cli.analytics, "track_event"),
            mock.patch.object(mcp_cli, "run_configure") as run_configure,
        ):
            result = runner.invoke(cli, ["configure", "--use-local", *args])
            assert result.exit_code == 0, result.output
        return run_configure.call_args.kwargs

    def test_a_refusal__reaches_the_mcp_flow(self):
        assert self._redirected("--no-install-skills")["skills_flag"] is False

    def test_a_request__reaches_it_too(self):
        assert self._redirected("--install-skills")["skills_flag"] is True

    def test_saying_nothing__stays_nothing(self):
        """Which is what lets the MCP flow install the pack by default."""
        assert self._redirected()["skills_flag"] is None


class TestTheRedirectReportsTheWholeFlow:
    """The redirect emits `opik mcp configure`'s events (`@entry_point`).

    Nothing here mocks `run_configure`: suppression is decided by the real frame
    chain.
    """

    @staticmethod
    def _reported(direct=False):
        from opik import environment_details
        from opik.analytics import api as analytics_api, worker as analytics_worker
        from opik.cli import mcp as mcp_cli
        from opik.config import OpikConfig
        from opik.configurator.mcp import install as mcp_install

        recorded = []

        class Recorder:
            def enqueue(self, event):
                # Like the real worker: run context at enqueue, session at send.
                event = event._replace(
                    properties={
                        **environment_details.run_context(),
                        **event.properties,
                    }
                )
                recorded.append(
                    (
                        event.name,
                        {**analytics_worker.session_properties(), **event.properties},
                    )
                )
                return True

        def flow(**kwargs):
            kwargs["progress"].redirect_to_mcp = True
            return assistants.Outcome(clients=0, skills=False)

        runner = CliRunner()
        with (
            # Module-level and deliberately not reset per run, so a test that
            # left it set would colour every test after it.
            mock.patch.object(environment_details, "_RUN_CONTEXT", {}),
            mock.patch.object(analytics_api, "_WORKER", Recorder()),
            mock.patch.object(analytics_api, "_DISABLED", False),
            mock.patch.object(analytics_api, "_ALREADY_REPORTED", set()),
            mock.patch.object(analytics_api, "_REPORTING_CODE", set()),
            mock.patch.object(
                configure_cli.interactive_helpers, "is_interactive", return_value=True
            ),
            mock.patch.object(
                configure_cli, "run_interactive_configure", side_effect=flow
            ),
            mock.patch.object(
                configure_cli.account_identity, "event_properties", return_value={}
            ),
            mock.patch.object(
                mcp_cli.account_identity, "event_properties", return_value={}
            ),
            mock.patch.object(
                mcp_cli.interactive_helpers, "is_interactive", return_value=True
            ),
            # Already configured by the time the redirect runs — which is the
            # point of the redirect: `opik configure` has just written this.
            mock.patch.object(
                mcp_cli.opik_config,
                "OpikConfig",
                return_value=OpikConfig(
                    url_override="https://www.comet.com/opik/api/",
                    workspace="acme-ai",
                    api_key="key",
                ),
            ),
            mock.patch.object(mcp_cli.mcp_targets, "detected_targets", return_value=[]),
            mock.patch.object(
                mcp_cli.assistants.mcp_installer,
                "setup_mcp_server",
                return_value=mcp_install.InstallReport(registered=()),
            ),
            mock.patch.object(
                mcp_cli.assistants.consent, "granted", return_value=False
            ),
        ):
            if direct:
                result = runner.invoke(cli, ["mcp", "configure"])
            else:
                result = runner.invoke(cli, ["configure", "--use-local"])

        assert result.exit_code == 0, result.output
        return recorded

    def test_both_commands_report_their_own_pair(self):
        assert [name for name, _ in self._reported()] == [
            "opik_python_sdk__configuration__configure",
            "opik_python_sdk__configuration__configure__result",
            "opik_python_sdk__configuration__mcp_configure",
            "opik_python_sdk__configuration__mcp_configure__result",
        ]

    def test_every_mcp_event_says_it_was_reached_through_configure(self):
        """Carried by the run context, so a later event gets it without asking."""
        reported = self._reported()

        assert [
            properties.get("invoked_via")
            for name, properties in reported
            if "mcp_configure" in name
        ] == ["opik_configure", "opik_configure"]

    def test_the_configure_events_predate_the_handover__so_they_do_not_claim_it(self):
        reported = self._reported()

        assert [
            properties.get("invoked_via")
            for name, properties in reported
            if "mcp_configure" not in name
        ] == [None, None]

    def test_a_direct_run_says_so(self):
        """The control, and the reason the funnel can separate the two."""
        reported = self._reported(direct=True)

        assert {properties.get("invoked_via") for _, properties in reported} == {
            "direct"
        }

    def test_one_session_covers_the_whole_handover(self):
        """Same process, so the four events are joinable without a correlation id."""
        reported = self._reported()

        assert len({properties["session_id"] for _, properties in reported}) == 1


class TestTheProjectLink:
    """The closing block links the project; a lookup only picks which link."""

    @staticmethod
    def _config(url_override="https://www.comet.com/opik/api/"):
        from types import SimpleNamespace

        return SimpleNamespace(
            url_override=url_override,
            workspace="acme-ai",
            api_key="key",
            check_tls_certificate=True,
        )

    @staticmethod
    def _listing(monkeypatch, content):
        monkeypatch.setattr(
            configure_cli.opik_rest_helpers, "list_projects", lambda **kwargs: content
        )

    def test_an_existing_project__links_its_page(self, monkeypatch):
        monkeypatch.setattr(configure_cli.opik_config, "OpikConfig", self._config)
        self._listing(
            monkeypatch,
            content=[
                {"name": "checkout-bot-v2", "id": "other"},
                {"name": "checkout-bot", "id": "0190-abc"},
            ],
        )

        assert configure_cli._project_url("checkout-bot") == (
            "https://www.comet.com/opik/acme-ai/projects/0190-abc/",
            True,
        )

    def test_no_such_project_yet__links_the_project_list(self, monkeypatch):
        """The name filter is a partial match, so a near miss is not the project."""
        monkeypatch.setattr(configure_cli.opik_config, "OpikConfig", self._config)
        self._listing(monkeypatch, content=[{"name": "checkout-bot-v2", "id": "x"}])

        assert configure_cli._project_url("checkout-bot") == (
            "https://www.comet.com/opik/acme-ai/projects",
            False,
        )

    def test_an_unreachable_backend__still_gives_a_link(self, monkeypatch):
        monkeypatch.setattr(configure_cli.opik_config, "OpikConfig", self._config)
        self._listing(monkeypatch, content=None)

        assert configure_cli._project_url("checkout-bot") == (
            "https://www.comet.com/opik/acme-ai/projects",
            False,
        )

    def test_a_workspace_name_is_one_path_segment(self, monkeypatch):
        """A `/`, `?` or `#` in it must not move the link somewhere else."""
        monkeypatch.setattr(
            configure_cli.opik_config,
            "OpikConfig",
            lambda: SimpleNamespace(**{**vars(self._config()), "workspace": "a/b?c"}),
        )
        self._listing(monkeypatch, content=[])

        assert configure_cli._project_url("checkout-bot")[0] == (
            "https://www.comet.com/opik/a%2Fb%3Fc/projects"
        )

    def test_a_local_opik__serves_the_ui_at_its_root(self, monkeypatch):
        """Only the Comet platform puts the UI under `/opik/`."""
        monkeypatch.setattr(
            configure_cli.opik_config,
            "OpikConfig",
            lambda: self._config(url_override="http://localhost:5173/api/"),
        )
        self._listing(monkeypatch, content=[])

        assert configure_cli._project_url("checkout-bot")[0] == (
            "http://localhost:5173/acme-ai/projects"
        )


class TestAskForConnection:
    """Which Opik `opik mcp configure` connects to: asked for the AI client, never
    saved to ~/.opik.config, and not taken from it either."""

    @pytest.fixture
    def config_file(self, monkeypatch, tmp_path):
        path = tmp_path / "opik.config"
        path.write_text(
            "[opik]\n"
            "url_override = https://www.comet.com/opik/api/\n"
            "api_key = saved-key\n"
            "workspace = saved-ws\n"
        )
        monkeypatch.setenv("OPIK_CONFIG_PATH", str(path))
        return path

    def test_local__the_saved_file_is_left_as_it_was(self, config_file):
        """It used to be overwritten with the local URL, after which every run
        reused that URL without asking again."""
        before = config_file.read_text()

        with (
            mock.patch(
                "opik.configurator.configure.opik_rest_helpers.is_instance_active",
                return_value=True,
            ),
            mock.patch.object(configure_cli.install_view, "render_configure_hint"),
        ):
            connection = configure_cli.ask_for_connection(
                configure_cli.interactive_helpers.DeploymentType.LOCAL
            )

        assert config_file.read_text() == before
        assert connection["use_local"] is True
        assert connection["base_url"] == "http://localhost:5173/"
        assert connection["api_key"] is None, "the saved key is not this Opik's"

    def test_local_found_without_asking__says_which(self, config_file):
        """Another local Opik on a different port would otherwise go unseen."""
        with (
            mock.patch(
                "opik.configurator.configure.opik_rest_helpers.is_instance_active",
                return_value=True,
            ),
            mock.patch.object(
                configure_cli.install_view, "render_configure_hint"
            ) as hint,
        ):
            configure_cli.ask_for_connection(
                configure_cli.interactive_helpers.DeploymentType.LOCAL
            )

        hint.assert_called_once_with("Using the local Opik at http://localhost:5173/")

    def test_local_url_typed__is_not_repeated_back(self, config_file):
        def active(url):
            return url == "http://localhost:5174/"

        with (
            mock.patch("opik.configurator.configure.is_interactive", return_value=True),
            mock.patch("builtins.input", return_value="http://localhost:5174"),
            mock.patch(
                "opik.configurator.configure.opik_rest_helpers.is_instance_active",
                side_effect=active,
            ),
            mock.patch.object(
                configure_cli.install_view, "render_configure_hint"
            ) as hint,
        ):
            connection = configure_cli.ask_for_connection(
                configure_cli.interactive_helpers.DeploymentType.LOCAL
            )

        assert connection["base_url"] == "http://localhost:5174/"
        assert not any("localhost:5174" in call.args[0] for call in hint.call_args_list)

    def test_self_hosted__asks_for_key_and_workspace__but_not_a_project(
        self, config_file
    ):
        """The project is where this SDK logs, not part of which Opik to reach."""
        before = config_file.read_text()
        questions = []

        def approve(question):
            questions.append(question)
            return True

        with (
            mock.patch("opik.configurator.configure.is_interactive", return_value=True),
            mock.patch("builtins.input", return_value="https://opik.acme.com"),
            mock.patch(
                "opik.configurator.configure.getpass.getpass", return_value="answer-key"
            ),
            mock.patch(
                "opik.configurator.configure.opik_rest_helpers.is_instance_active",
                return_value=True,
            ),
            mock.patch(
                "opik.configurator.configure.opik_rest_helpers.is_api_key_correct",
                return_value=True,
            ),
            mock.patch.object(
                configure_cli.opik_configure.OpikConfigurator,
                "_get_default_workspace",
                return_value="team-ws",
            ),
            mock.patch(
                "opik.configurator.configure.ask_user_for_approval",
                side_effect=approve,
            ),
        ):
            connection = configure_cli.ask_for_connection(
                configure_cli.interactive_helpers.DeploymentType.SELF_HOSTED
            )

        assert config_file.read_text() == before
        assert connection["api_key"] == "answer-key"
        assert connection["workspace"] == "team-ws"
        assert connection["self_hosted_comet"] is True
        assert connection["base_url"] == "https://opik.acme.com/"
        assert questions == ['Use the "team-ws" workspace?']
