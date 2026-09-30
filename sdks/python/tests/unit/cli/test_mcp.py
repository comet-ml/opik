"""Tests for the ``opik mcp configure`` command."""

import pathlib
from types import SimpleNamespace
from unittest.mock import patch

import click
from click.testing import CliRunner

from opik.cli import assistants
from opik.cli import cli
from opik.cli import mcp as mcp_cli
from opik.configurator import consent
from opik.configurator.mcp import install as mcp_install
from opik.config import OpikConfig


def _config(**overrides) -> OpikConfig:
    values = dict(url_override="https://www.comet.com/opik/api/", workspace="acme-ai")
    values.update(overrides)
    return OpikConfig(**values)


class TestResolveSetupParams:
    def test_cloud__no_url_flags(self):
        params = mcp_cli._resolve_setup_params(
            _config(api_key="key", url_override="https://www.comet.com/opik/api/")
        )
        assert params["use_local"] is False
        assert params["self_hosted_comet"] is False
        assert params["api_url"] == "https://www.comet.com/opik/api/"

    def test_self_hosted_comet__detected_from_opik_api_path(self):
        params = mcp_cli._resolve_setup_params(
            _config(api_key="key", url_override="https://opik.acme.com/opik/api/")
        )
        assert params["self_hosted_comet"] is True
        assert params["use_local"] is False
        assert params["base_url"] == "https://opik.acme.com/"

    def test_localhost__is_use_local(self):
        params = mcp_cli._resolve_setup_params(
            _config(api_key=None, url_override="http://localhost:5173/api/")
        )
        assert params["use_local"] is True
        assert params["self_hosted_comet"] is False

    def test_self_hosted_oss__non_opik_path_is_use_local(self):
        params = mcp_cli._resolve_setup_params(
            _config(api_key=None, url_override="https://opik.acme.com/api/")
        )
        assert params["use_local"] is True
        assert params["self_hosted_comet"] is False


class TestInstallCommand:
    def test_install__reads_config_and_calls_setup(self):
        runner = CliRunner()
        with (
            patch.object(
                mcp_cli.opik_config, "OpikConfig", return_value=_config(api_key="key")
            ),
            patch.object(
                mcp_cli.interactive_helpers, "is_interactive", return_value=True
            ),
            patch.object(mcp_cli.assistants, "setup") as setup_spy,
        ):
            result = runner.invoke(cli, ["mcp", "configure"])

        assert result.exit_code == 0
        setup_spy.assert_called_once()
        assert setup_spy.call_args.args[0]["api_key"] == "key"
        assert setup_spy.call_args.args[0]["workspace"] == "acme-ai"
        assert setup_spy.call_args.kwargs["force_local_server"] is False

    def test_install__local_server_flag__forces_local(self):
        runner = CliRunner()
        with (
            patch.object(
                mcp_cli.opik_config, "OpikConfig", return_value=_config(api_key="key")
            ),
            patch.object(
                mcp_cli.interactive_helpers, "is_interactive", return_value=True
            ),
            patch.object(mcp_cli.assistants, "setup") as setup_spy,
        ):
            result = runner.invoke(cli, ["mcp", "configure", "--local-server"])

        assert result.exit_code == 0
        setup_spy.assert_called_once()
        assert setup_spy.call_args.kwargs["force_local_server"] is True

    def test_install__non_interactive__errors(self):
        runner = CliRunner()
        with (
            patch.object(
                mcp_cli.interactive_helpers, "is_interactive", return_value=False
            ),
            patch.object(mcp_cli.assistants, "setup") as setup_spy,
        ):
            result = runner.invoke(cli, ["mcp", "configure"])

        assert result.exit_code != 0
        assert "--ai-client" in result.output, "the error must name the remedy"
        setup_spy.assert_not_called()

    def test_install__no_config_user_declines__errors(self):
        runner = CliRunner()
        with (
            patch.object(
                mcp_cli.opik_config, "OpikConfig", return_value=_config(api_key=None)
            ),
            patch.object(
                mcp_cli.interactive_helpers, "is_interactive", return_value=True
            ),
            patch.object(
                mcp_cli.configure_cli, "run_interactive_configure"
            ) as configure_spy,
            patch.object(mcp_cli.assistants, "setup") as setup_spy,
        ):
            result = runner.invoke(cli, ["mcp", "configure"], input="n\n")

        assert result.exit_code != 0
        assert "opik configure" in result.output
        configure_spy.assert_not_called()
        setup_spy.assert_not_called()

    def test_install__no_config_user_accepts__runs_configure_then_installs(self):
        runner = CliRunner()
        configs = iter([_config(api_key=None), _config(api_key="new-key")])
        with (
            patch.object(
                mcp_cli.opik_config, "OpikConfig", side_effect=lambda: next(configs)
            ),
            patch.object(
                mcp_cli.interactive_helpers, "is_interactive", return_value=True
            ),
            patch.object(
                mcp_cli.configure_cli, "run_interactive_configure"
            ) as configure_spy,
            patch.object(mcp_cli.assistants, "setup") as setup_spy,
        ):
            result = runner.invoke(cli, ["mcp", "configure"], input="y\n")

        assert result.exit_code == 0
        configure_spy.assert_called_once_with(install_mcp=False)
        setup_spy.assert_called_once()
        assert setup_spy.call_args.args[0]["api_key"] == "new-key"

    def test_status__lists_sdk_env_and_host_drift(self):
        runner = CliRunner()
        host = mcp_cli.mcp_status.HostStatus(
            display_name="Claude Code",
            config_path=pathlib.Path("/home/u/.claude.json"),
            detected=True,
            registered=True,
            transport=mcp_cli.mcp_status.TRANSPORT_LOCAL,
            points_to="http://localhost:5173/api/",
            workspace="default",
            in_sync=False,
        )
        with (
            patch.object(
                mcp_cli.opik_config, "OpikConfig", return_value=_config(api_key="key")
            ),
            patch.object(
                mcp_cli.mcp_status, "collect_host_statuses", return_value=[host]
            ),
        ):
            result = runner.invoke(cli, ["mcp", "status"])

        assert result.exit_code == 0
        assert "Your Opik configuration" in result.output
        assert "configured for 1 AI client" in result.output
        assert "Claude Code" in result.output
        assert "OUT OF SYNC with your Opik configuration" in result.output
        assert "http://localhost:5173/api/" in result.output
        assert "default" in result.output

    def test_status__none_configured__suggests_configure(self):
        runner = CliRunner()
        with (
            patch.object(
                mcp_cli.opik_config, "OpikConfig", return_value=_config(api_key="key")
            ),
            patch.object(mcp_cli.mcp_status, "collect_host_statuses", return_value=[]),
        ):
            result = runner.invoke(cli, ["mcp", "status"])

        assert result.exit_code == 0
        assert "not configured for any AI client" in result.output
        assert "opik mcp configure" in result.output

    def test_install__local_without_api_key__proceeds(self):
        runner = CliRunner()
        with (
            patch.object(
                mcp_cli.opik_config,
                "OpikConfig",
                return_value=_config(
                    api_key=None, url_override="http://localhost:5173/api/"
                ),
            ),
            patch.object(
                mcp_cli.interactive_helpers, "is_interactive", return_value=True
            ),
            patch.object(mcp_cli.assistants, "setup") as setup_spy,
        ):
            result = runner.invoke(cli, ["mcp", "configure"])

        assert result.exit_code == 0
        setup_spy.assert_called_once()
        assert setup_spy.call_args.args[0]["use_local"] is True


class TestIdentityIsReportedWithBothEvents:
    """Both events of the pair have to name the account, or neither joins.

    The entry event is what a drop-off is counted from, so identity only on the
    result event would attribute the runs that finished and none of the ones worth
    acting on.
    """

    def test_configure__entry_and_result__both_carry_the_account(self):
        runner = CliRunner()
        identity = {"user_id": "someone", "identity_lookup": "resolved"}

        with (
            patch.object(
                mcp_cli.opik_config, "OpikConfig", return_value=_config(api_key="key")
            ),
            patch.object(
                mcp_cli.interactive_helpers, "is_interactive", return_value=True
            ),
            patch.object(mcp_cli.assistants, "setup"),
            patch.object(
                mcp_cli.account_identity, "event_properties", return_value=identity
            ),
            patch.object(mcp_cli.analytics, "track_event") as track,
        ):
            result = runner.invoke(cli, ["mcp", "configure"])

        assert result.exit_code == 0
        assert len(track.call_args_list) == 2
        for call in track.call_args_list:
            assert call.kwargs["user_id"] == "someone"
            assert call.kwargs["identity_lookup"] == "resolved"


class TestHostFlag:
    """`--host` is what lets an agent, a Dockerfile, or CI run this at all."""

    def test_configure__host_flag__passes_key_through(self):
        runner = CliRunner()
        with (
            patch.object(
                mcp_cli.opik_config, "OpikConfig", return_value=_config(api_key="key")
            ),
            patch.object(
                mcp_cli.interactive_helpers, "is_interactive", return_value=True
            ),
            patch.object(mcp_cli.assistants, "setup") as setup_spy,
        ):
            result = runner.invoke(cli, ["mcp", "configure", "--ai-client", "codex"])

        assert result.exit_code == 0
        assert setup_spy.call_args.kwargs["host_keys"] == ["codex"]

    def test_configure__repeated_host_flag__passes_every_key(self):
        runner = CliRunner()
        with (
            patch.object(
                mcp_cli.opik_config, "OpikConfig", return_value=_config(api_key="key")
            ),
            patch.object(
                mcp_cli.interactive_helpers, "is_interactive", return_value=True
            ),
            patch.object(mcp_cli.assistants, "setup") as setup_spy,
        ):
            result = runner.invoke(
                cli,
                ["mcp", "configure", "--ai-client", "codex", "--ai-client", "cursor"],
            )

        assert result.exit_code == 0
        assert setup_spy.call_args.kwargs["host_keys"] == ["codex", "cursor"]

    def test_configure__duplicate_host_flag__deduplicates(self):
        runner = CliRunner()
        with (
            patch.object(
                mcp_cli.opik_config, "OpikConfig", return_value=_config(api_key="key")
            ),
            patch.object(
                mcp_cli.interactive_helpers, "is_interactive", return_value=True
            ),
            patch.object(mcp_cli.assistants, "setup") as setup_spy,
        ):
            result = runner.invoke(
                cli,
                ["mcp", "configure", "--ai-client", "codex", "--ai-client", "codex"],
            )

        assert result.exit_code == 0
        assert setup_spy.call_args.kwargs["host_keys"] == ["codex"]

    def test_configure__no_host_flag__leaves_detection_to_the_installer(self):
        runner = CliRunner()
        with (
            patch.object(
                mcp_cli.opik_config, "OpikConfig", return_value=_config(api_key="key")
            ),
            patch.object(
                mcp_cli.interactive_helpers, "is_interactive", return_value=True
            ),
            patch.object(mcp_cli.assistants, "setup") as setup_spy,
        ):
            result = runner.invoke(cli, ["mcp", "configure"])

        assert result.exit_code == 0
        assert setup_spy.call_args.kwargs["host_keys"] is None

    def test_configure__unknown_host__is_rejected_by_the_parser(self):
        runner = CliRunner()
        result = runner.invoke(cli, ["mcp", "configure", "--ai-client", "emacs"])

        assert result.exit_code != 0
        assert "emacs" in result.output

    def test_configure__host_all__expands_to_detected_hosts(self):
        runner = CliRunner()
        detected = [
            mcp_cli.mcp_targets.find_target("cursor"),
            mcp_cli.mcp_targets.find_target("codex"),
        ]
        with (
            patch.object(
                mcp_cli.opik_config, "OpikConfig", return_value=_config(api_key="key")
            ),
            patch.object(
                mcp_cli.interactive_helpers, "is_interactive", return_value=True
            ),
            patch.object(
                mcp_cli.mcp_targets, "detected_targets", return_value=detected
            ),
            patch.object(mcp_cli.assistants, "setup") as setup_spy,
        ):
            result = runner.invoke(cli, ["mcp", "configure", "--ai-client", "all"])

        assert result.exit_code == 0
        assert setup_spy.call_args.kwargs["host_keys"] == ["cursor", "codex"]

    def test_configure__host_all_with_nothing_detected__errors(self):
        runner = CliRunner()
        with (
            patch.object(mcp_cli.mcp_targets, "detected_targets", return_value=[]),
            patch.object(mcp_cli.assistants, "setup") as setup_spy,
        ):
            result = runner.invoke(cli, ["mcp", "configure", "--ai-client", "all"])

        assert result.exit_code != 0
        assert "no supported AI client" in result.output
        setup_spy.assert_not_called()

    def test_configure__non_interactive_with_host__refuses(self):
        """`--host` says which assistant, not whether we may write unattended."""
        runner = CliRunner()
        with (
            patch.object(
                mcp_cli.opik_config, "OpikConfig", return_value=_config(api_key="key")
            ),
            patch.object(
                mcp_cli.interactive_helpers, "is_interactive", return_value=False
            ),
            patch.object(mcp_cli.assistants, "setup") as setup_spy,
        ):
            result = runner.invoke(cli, ["mcp", "configure", "--ai-client", "codex"])

        assert result.exit_code == 0
        setup_spy.assert_called_once()

    def test_configure__non_interactive_without_client__refuses_with_a_remedy(self):
        runner = CliRunner()
        with (
            patch.object(
                mcp_cli.interactive_helpers, "is_interactive", return_value=False
            ),
            patch.object(mcp_cli.assistants, "setup") as setup_spy,
        ):
            result = runner.invoke(cli, ["mcp", "configure"])

        assert result.exit_code != 0
        assert "--ai-client" in result.output, "the error must name the remedy"
        setup_spy.assert_not_called()

    def test_configure__non_interactive_host_but_unconfigured__refuses(self):
        """Refused for the terminal before Opik configuration is even considered."""
        runner = CliRunner()
        with (
            patch.object(
                mcp_cli.opik_config, "OpikConfig", return_value=_config(api_key=None)
            ),
            patch.object(
                mcp_cli.interactive_helpers, "is_interactive", return_value=False
            ),
            patch.object(
                mcp_cli.configure_cli, "run_interactive_configure"
            ) as configure_spy,
            patch.object(mcp_cli.assistants, "setup") as setup_spy,
        ):
            result = runner.invoke(cli, ["mcp", "configure", "--ai-client", "codex"])

        assert result.exit_code != 0
        # A named client gets past the terminal gate, so the next thing missing is
        # Opik's own configuration — and that message names its own remedy.
        assert "OPIK_API_KEY" in result.output, "the error must name the remedy"
        configure_spy.assert_not_called()
        setup_spy.assert_not_called()


class TestDelegatesToTheSharedStep:
    """`opik mcp configure` and `opik configure` run the same assistant step."""

    def test_configure__hands_the_connection_block_and_flags_over(self):
        runner = CliRunner()
        with (
            patch.object(
                mcp_cli.opik_config, "OpikConfig", return_value=_config(api_key="key")
            ),
            patch.object(
                mcp_cli.interactive_helpers, "is_interactive", return_value=True
            ),
            patch.object(mcp_cli.assistants, "setup") as setup_spy,
        ):
            result = runner.invoke(
                cli, ["mcp", "configure", "--ai-client", "cursor", "--no-skills"]
            )

        assert result.exit_code == 0
        kwargs = setup_spy.call_args.kwargs
        assert kwargs["host_keys"] == ["cursor"]
        assert kwargs["skills"].reason is consent.Reason.DECLINED
        assert kwargs["install_mcp"] is True, "running this command is the consent"
        assert setup_spy.call_args.args[0]["api_key"] == "key"

    def test_configure__local_server_flag__is_passed_through(self):
        runner = CliRunner()
        with (
            patch.object(
                mcp_cli.opik_config, "OpikConfig", return_value=_config(api_key="key")
            ),
            patch.object(
                mcp_cli.interactive_helpers, "is_interactive", return_value=True
            ),
            patch.object(mcp_cli.assistants, "setup") as setup_spy,
        ):
            runner.invoke(
                cli, ["mcp", "configure", "--ai-client", "cursor", "--local-server"]
            )

        assert setup_spy.call_args.kwargs["force_local_server"] is True


class TestResultEventCarriesTheFunnelProperties:
    """`opik mcp configure` is the control group for the configure funnel.

    Running this command *is* the consent for the server, so a zero here is a
    failure rather than a refusal — which is only a useful comparison if both
    commands report the same properties.
    """

    @staticmethod
    def _result_event(outcome, detected=("Cursor", "Codex")):
        runner = CliRunner()
        with (
            patch.object(
                mcp_cli.opik_config, "OpikConfig", return_value=_config(api_key="key")
            ),
            patch.object(
                mcp_cli.interactive_helpers, "is_interactive", return_value=True
            ),
            patch.object(
                mcp_cli.mcp_targets,
                "detected_targets",
                return_value=[
                    SimpleNamespace(display_name=name, key=name.lower())
                    for name in detected
                ],
            ),
            patch.object(mcp_cli.assistants, "setup", return_value=outcome),
            patch.object(mcp_cli.account_identity, "event_properties", return_value={}),
            patch.object(mcp_cli.analytics, "track_event") as track,
        ):
            assert runner.invoke(cli, ["mcp", "configure"]).exit_code == 0
        return track.call_args_list[-1].kwargs

    def test_reports_detected_clients(self):
        event = self._result_event(assistants.Outcome(clients=1, skills=True))

        assert event["detected_clients"] == 2

    def test_reports_failures_and_verification(self):
        event = self._result_event(
            assistants.Outcome(clients=1, skills=True, failed_clients=1, verified=False)
        )

        assert event["clients_failed"] == 1
        assert event["verification_succeeded"] is False

    def test_nothing_detected__is_zero_not_absent(self):
        event = self._result_event(
            assistants.Outcome(clients=0, skills=False), detected=()
        )

        assert event["detected_clients"] == 0
        assert event["clients_written"] == 0

    def test_running_the_command_is_the_consent__so_it_is_always_a_request(self):
        """Which is what makes this command the control group.

        There is no permission question here — typing it is the permission — so
        `mcp_decision` cannot vary, and a zero can never be read as a refusal.
        """
        event = self._result_event(
            assistants.Outcome(clients=0, skills=False, mcp_declined=True)
        )

        assert event["mcp_decision"] == "requested"

    def test_picker_skipped__is_how_a_zero_says_the_user_chose_no_client(self):
        """The only refusal this command can record, so it has to be recorded.

        `mcp_decision` is pinned to `requested` above, which leaves nothing else
        to tell a run that wrote nothing because the user picked nothing from
        one where every write failed.
        """
        event = self._result_event(
            assistants.Outcome(clients=0, skills=False, mcp_declined=True)
        )

        assert event["picker_skipped"] is True

    def test_clients_registered__is_not_a_skipped_picker(self):
        event = self._result_event(
            assistants.Outcome(clients=1, skills=True, registered_clients=("cursor",))
        )

        assert event["picker_skipped"] is False


class TestResultEventCarriesTheHandoff:
    """How the run ends is the point of the command, so the funnel must see it.

    The handoff replaces this process with the user's agent, so it is resolved
    before the result event rather than after: anything reported later would
    never be reported at all.
    """

    @staticmethod
    def _result_event(
        registered,
        traced_project=None,
        can_launch=True,
        accepted=True,
        abort=False,
        interactive=True,
        args=(),
    ):
        runner = CliRunner()
        outcome = assistants.Outcome(
            clients=len(registered), skills=False, registered_clients=registered
        )
        with (
            patch.object(
                mcp_cli.opik_config, "OpikConfig", return_value=_config(api_key="key")
            ),
            patch.object(
                mcp_cli.interactive_helpers, "is_interactive", return_value=interactive
            ),
            patch.object(mcp_cli.mcp_targets, "detected_targets", return_value=[]),
            patch.object(
                mcp_cli.mcp_targets,
                "find_target",
                return_value=SimpleNamespace(display_name="Claude Code"),
            ),
            patch.object(mcp_cli.assistants, "setup", return_value=outcome),
            patch.object(mcp_cli.account_identity, "event_properties", return_value={}),
            patch.object(
                mcp_cli.mcp_handoff, "traced_project", return_value=traced_project
            ),
            patch.object(mcp_cli.mcp_handoff, "can_launch", return_value=can_launch),
            patch.object(mcp_cli.mcp_handoff, "launch"),
            patch(
                "click.confirm",
                side_effect=click.Abort if abort else None,
                return_value=accepted,
            ),
            patch.object(mcp_cli.install_view, "render_handoff"),
            patch.object(mcp_cli.install_view, "render_handoff_offer"),
            patch.object(mcp_cli.install_view, "render_handoff_declined"),
            patch.object(mcp_cli.install_view, "render_prompt_to_paste"),
            patch.object(mcp_cli.analytics, "track_event") as track,
        ):
            result = runner.invoke(cli, ["mcp", "configure", *args])
            assert result.exit_code == 0, result.output
        return track.call_args_list[-1].kwargs

    def test_single_client_with_traces__launches_on_the_diagnose_prompt(self):
        event = self._result_event(("claude-code",), traced_project="my-app")

        assert event["handoff"] == "launch"
        assert event["closing_prompt"] == "diagnose"

    def test_single_client_without_traces__opens_on_the_instrument_prompt(self):
        """Which is also the answer when the workspace lookup fails."""
        event = self._result_event(("claude-code",), traced_project=None)

        assert event["closing_prompt"] == "instrument"

    def test_client_we_cannot_start__the_prompt_is_shown_instead(self):
        event = self._result_event(("cursor",), can_launch=False)

        assert event["handoff"] == "prompt_shown"

    def test_saying_no_to_the_offer__is_its_own_ending(self):
        """The last stage of the funnel, and the only one the user drives.

        A registered server nobody wanted to try is a different outcome from one
        that ended inside the agent, and neither is a failure.
        """
        event = self._result_event(("claude-code",), accepted=False)

        assert event["handoff"] == "declined"
        # Still resolved, because the question the user turned down is the same
        # one a launch would have opened on.
        assert event["closing_prompt"] == "instrument"

    def test_ctrl_c_at_the_offer__ends_the_same_way_as_saying_no(self):
        """Nothing is left half-done by then: the server and the pack are in.

        Letting the abort propagate would lose the result event, which would make
        the run that got furthest the one the funnel cannot see.
        """
        event = self._result_event(("claude-code",), abort=True)

        assert event["handoff"] == "declined"

    def test_no_terminal__nothing_to_hand_over_to(self):
        """A named client is what lets the command run unattended at all."""
        event = self._result_event(
            ("claude-code",), interactive=False, args=("--ai-client", "claude-code")
        )

        assert event["handoff"] == "no_terminal"
        assert event["closing_prompt"] == ""

    def test_several_clients__no_single_agent_to_end_in(self):
        event = self._result_event(("claude-code", "cursor"))

        assert event["handoff"] == "not_single_client"

    def test_ctrl_c_at_the_picker__is_not_filed_as_a_client_count(self):
        """A cancelled run registers nothing, which the count check also matches.

        Whichever is tested first wins, and `not_single_client` describes a
        scripted run that wrote several configurations — the opposite of a run
        that wrote none.
        """
        runner = CliRunner()
        outcome = assistants.Outcome(
            clients=0, skills=False, registered_clients=(), cancelled=True
        )
        with (
            patch.object(
                mcp_cli.opik_config, "OpikConfig", return_value=_config(api_key="key")
            ),
            patch.object(
                mcp_cli.interactive_helpers, "is_interactive", return_value=True
            ),
            patch.object(mcp_cli.mcp_targets, "detected_targets", return_value=[]),
            patch.object(mcp_cli.assistants, "setup", return_value=outcome),
            patch.object(mcp_cli.account_identity, "event_properties", return_value={}),
            patch.object(mcp_cli.install_view, "render_restart_note") as restart,
            patch.object(mcp_cli.analytics, "track_event") as track,
        ):
            result = runner.invoke(cli, ["mcp", "configure"])
            assert result.exit_code == 0, result.output

        assert track.call_args_list[-1].kwargs["handoff"] == "cancelled"
        # Nothing was written, so there is nothing to restart for.
        restart.assert_not_called()


class TestResultEventCarriesTheConnectionSignals:
    """What the adoption board (dashboard 2057363) needs to be joinable.

    That board counts people who CONNECTED an MCP server, keyed on the Comet
    login for the hosted transport and on the API key digest for the local one.
    A configure run that does not say which transport it registered cannot pick
    the right key, and one that does not say whether the sign-in worked cannot
    explain the drop between registering and connecting.
    """

    @staticmethod
    def _result_event(install_report):
        runner = CliRunner()
        with (
            patch.object(
                mcp_cli.opik_config, "OpikConfig", return_value=_config(api_key="key")
            ),
            patch.object(
                mcp_cli.interactive_helpers, "is_interactive", return_value=True
            ),
            patch.object(mcp_cli.mcp_targets, "detected_targets", return_value=[]),
            patch.object(
                mcp_cli.assistants.mcp_installer,
                "setup_mcp_server",
                return_value=install_report,
            ),
            patch.object(mcp_cli.assistants.consent, "granted", return_value=False),
            patch.object(
                mcp_cli.mcp_targets,
                "find_target",
                return_value=SimpleNamespace(display_name="Claude Code"),
            ),
            patch.object(mcp_cli.mcp_handoff, "traced_project", return_value=None),
            patch.object(mcp_cli.mcp_handoff, "can_launch", return_value=True),
            patch.object(mcp_cli.mcp_handoff, "launch"),
            patch.object(mcp_cli.install_view, "render_handoff"),
            patch.object(mcp_cli.install_view, "render_prompt_to_paste"),
            patch.object(mcp_cli.account_identity, "event_properties", return_value={}),
            patch.object(mcp_cli.analytics, "track_event") as track,
        ):
            result = runner.invoke(cli, ["mcp", "configure"])
            assert result.exit_code == 0, result.output
        return track.call_args_list[-1].kwargs

    def test_hosted_server__transport_and_a_successful_sign_in_are_reported(self):
        event = self._result_event(
            mcp_install.InstallReport(
                registered=("claude-code",),
                verified=True,
                transport="remote",
                sign_in="succeeded",
            )
        )

        assert event["transport"] == "remote"
        assert event["sign_in"] == "succeeded"

    def test_sign_in_failed__is_not_hidden_by_a_passing_verification(self):
        """The one case `verification_succeeded` cannot see.

        On the hosted transport it is a 401/403 reachability probe, which passes
        just as well for a user who never signed in.
        """
        event = self._result_event(
            mcp_install.InstallReport(
                registered=("claude-code",),
                verified=True,
                transport="remote",
                sign_in="failed",
            )
        )

        assert event["verification_succeeded"] is True
        assert event["sign_in"] == "failed"

    def test_sign_in_failed__the_agent_is_not_launched_into_a_toolless_server(self):
        event = self._result_event(
            mcp_install.InstallReport(
                registered=("claude-code",),
                verified=True,
                transport="remote",
                sign_in="failed",
            )
        )

        assert event["handoff"] == "sign_in_failed"

    def test_local_server__says_so__and_has_no_sign_in_to_attempt(self):
        event = self._result_event(
            mcp_install.InstallReport(
                registered=("cursor",),
                verified=True,
                transport="local_stdio",
            )
        )

        assert event["transport"] == "local_stdio"
        assert event["sign_in"] == "not_attempted"
