"""Tests for the ``opik mcp configure`` command."""

import pathlib
from types import SimpleNamespace
from unittest.mock import Mock, patch

import click
import pytest
from click.testing import CliRunner

from opik.cli import assistants
from opik.cli import cli
from opik.cli import install_view
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

    @staticmethod
    def _run_without_config(deployment, connection=None):
        """`opik mcp configure` on a machine with no Opik config."""
        runner = CliRunner()
        order = []

        def ask_deployment(question):
            order.append(("deployment", question))
            if isinstance(deployment, BaseException):
                raise deployment
            return deployment

        def setup(*args, **kwargs):
            order.append(("setup",))
            return assistants.NOTHING_DONE

        with (
            patch.object(
                mcp_cli.opik_config,
                "OpikConfig",
                side_effect=lambda **values: _config(**{"api_key": None, **values}),
            ),
            patch.object(
                mcp_cli.interactive_helpers, "is_interactive", return_value=True
            ),
            patch.object(mcp_cli.mcp_targets, "detected_targets", return_value=[]),
            patch.object(
                mcp_cli.configure_cli,
                "ask_for_deployment_type",
                side_effect=ask_deployment,
            ),
            patch.object(
                mcp_cli.configure_cli, "ask_for_connection", return_value=connection
            ) as configure_spy,
            patch.object(mcp_cli.assistants, "setup", side_effect=setup) as setup_spy,
            patch.object(mcp_cli.install_view, "render_mcp_banner"),
            patch.object(mcp_cli.account_identity, "event_properties", return_value={}),
            patch.object(mcp_cli.analytics, "track_event"),
        ):
            result = runner.invoke(cli, ["mcp", "configure"])
        return result, order, configure_spy, setup_spy

    def test_no_config__the_deployment_comes_before_the_client_step(self):
        """The funnel orders them so: the picker is the step after the deployment."""
        result, order, _, _ = self._run_without_config(
            mcp_cli.interactive_helpers.DeploymentType.CLOUD
        )

        assert result.exit_code == 0, result.output
        assert order == [("deployment", mcp_cli.MCP_DEPLOYMENT_QUESTION), ("setup",)]

    def test_no_config__the_real_picker_runs_after_the_deployment(self):
        """Through the real setup path, so moving or dropping the picker fails here."""
        runner = CliRunner()
        order = []
        detected = [mcp_cli.mcp_targets.find_target("cursor")]

        class Picker(install_view.RichInstallView):
            def choose_hosts(self, title, candidates):
                order.append("client")
                return None  # Cancel, so nothing is installed.

        def ask_deployment(question):
            order.append("deployment")
            return mcp_cli.interactive_helpers.DeploymentType.CLOUD

        with (
            patch.object(
                mcp_cli.opik_config, "OpikConfig", return_value=_config(api_key=None)
            ),
            patch.object(
                mcp_cli.interactive_helpers, "is_interactive", return_value=True
            ),
            patch.object(
                mcp_cli.mcp_targets, "detected_targets", return_value=detected
            ),
            patch.object(
                mcp_cli.configure_cli,
                "ask_for_deployment_type",
                side_effect=ask_deployment,
            ),
            patch.object(
                mcp_install.mcp_detection,
                "detect_hosted_mcp_server",
                return_value="https://www.comet.com/opik/api/v1/mcp",
            ),
            patch.object(mcp_cli.assistants.install_view, "RichInstallView", Picker),
            patch.object(mcp_cli.install_view, "render_mcp_banner"),
            patch.object(mcp_cli.account_identity, "event_properties", return_value={}),
            patch.object(mcp_cli.analytics, "track_event"),
        ):
            result = runner.invoke(cli, ["mcp", "configure"])

        assert result.exit_code == 0, result.output
        assert order == ["deployment", "client"]

    def test_no_config__the_question_is_about_the_ai_client_not_traces(self):
        """This is `opik mcp configure`; where traces go is `opik configure`'s question."""
        assert "AI client" in mcp_cli.MCP_DEPLOYMENT_QUESTION
        assert "traces" not in mcp_cli.MCP_DEPLOYMENT_QUESTION

    def test_no_config__cloud__goes_to_oauth_without_an_api_key_or_a_config(self):
        result, _, configure_spy, setup_spy = self._run_without_config(
            mcp_cli.interactive_helpers.DeploymentType.CLOUD
        )

        assert result.exit_code == 0, result.output
        configure_spy.assert_not_called()
        params = setup_spy.call_args.args[0]
        assert params["api_key"] is None
        assert params["api_url"] == mcp_cli.opik_config.OPIK_URL_CLOUD

    def test_no_config__self_hosted__connects_to_the_opik_the_answers_name(self):
        """The answers go to the AI client, not to ~/.opik.config."""
        connection = mcp_cli._resolve_setup_params(
            _config(api_key="new-key", url_override="https://opik.acme.com/opik/api/")
        )

        result, _, configure_spy, setup_spy = self._run_without_config(
            mcp_cli.interactive_helpers.DeploymentType.SELF_HOSTED,
            connection=connection,
        )

        assert result.exit_code == 0, result.output
        configure_spy.assert_called_once_with(
            mcp_cli.interactive_helpers.DeploymentType.SELF_HOSTED
        )
        assert setup_spy.call_args.args[0] == connection

    def test_no_config__ctrl_c_at_the_deployment__writes_nothing(self):
        result, _, configure_spy, setup_spy = self._run_without_config(click.Abort())

        assert result.exit_code != 0
        configure_spy.assert_not_called()
        setup_spy.assert_not_called()

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


class TestTheSavedOpikConfiguration:
    """Read, never written: a reused one is named up front, and the flag sets it
    aside for a run that should connect somewhere else."""

    @staticmethod
    def _run(saved, args=(), deployment=None, connection=None, interactive=True):
        runner = CliRunner()

        def opik_config_for(**values):
            # No arguments: the saved configuration. With them: one built for a
            # connection, as the identity lookup does.
            return _config(**values) if values else saved

        with (
            patch.object(
                mcp_cli.opik_config, "OpikConfig", side_effect=opik_config_for
            ),
            patch.object(
                mcp_cli.interactive_helpers,
                "is_interactive",
                return_value=interactive,
            ),
            patch.object(mcp_cli.mcp_targets, "detected_targets", return_value=[]),
            patch.object(
                mcp_cli.configure_cli,
                "ask_for_deployment_type",
                return_value=deployment,
            ) as asked,
            patch.object(
                mcp_cli.configure_cli, "ask_for_connection", return_value=connection
            ) as connection_asked,
            patch.object(
                mcp_cli.assistants, "setup", return_value=assistants.NOTHING_DONE
            ) as setup,
            patch.object(mcp_cli.install_view, "render_mcp_banner"),
            patch.object(mcp_cli.install_view, "render_connection") as named,
            patch.object(
                mcp_cli.account_identity, "event_properties", return_value={}
            ) as identity,
            patch.object(mcp_cli.analytics, "track_event") as track,
        ):
            result = runner.invoke(cli, ["mcp", "configure", *args])
        return SimpleNamespace(
            result=result,
            asked=asked,
            connection_asked=connection_asked,
            setup=setup,
            named=named,
            identity=identity,
            track=track,
        )

    def test_a_usable_one__is_used__and_named_before_anything_else(self):
        run = self._run(saved=_config(api_key="key"))

        assert run.result.exit_code == 0, run.result.output
        run.asked.assert_not_called()
        run.named.assert_called_once_with(
            opik_url="https://www.comet.com/",
            workspace="acme-ai",
            source="your Opik configuration",
        )
        assert run.setup.call_args.args[0]["api_key"] == "key"

    def test_a_local_one__is_named_without_its_one_workspace(self):
        run = self._run(
            saved=_config(api_key=None, url_override="http://localhost:5173/api/")
        )

        assert run.named.call_args.kwargs["opik_url"] == "http://localhost:5173/"
        assert run.named.call_args.kwargs["workspace"] is None

    def test_after_opik_configure__it_is_not_named_again(self):
        """That run has just shown the settings it wrote."""
        with (
            patch.object(
                mcp_cli.opik_config, "OpikConfig", return_value=_config(api_key="key")
            ),
            patch.object(
                mcp_cli.interactive_helpers, "is_interactive", return_value=True
            ),
            patch.object(mcp_cli.mcp_targets, "detected_targets", return_value=[]),
            patch.object(
                mcp_cli.assistants, "setup", return_value=assistants.NOTHING_DONE
            ),
            patch.object(mcp_cli.install_view, "render_connection") as named,
            patch.object(mcp_cli.account_identity, "event_properties", return_value={}),
            patch.object(mcp_cli.analytics, "track_event"),
        ):
            mcp_cli.run_configure(
                local_server=False,
                hosts=(),
                skills_flag=None,
                ignore_opik_config=False,
                invoked_via="opik_configure",
            )

        named.assert_not_called()

    def test_the_flag__asks_which_opik__even_with_a_usable_one(self):
        run = self._run(
            saved=_config(api_key="saved-key"),
            args=("--ignore-opik-config",),
            deployment=mcp_cli.interactive_helpers.DeploymentType.CLOUD,
        )

        assert run.result.exit_code == 0, run.result.output
        run.asked.assert_called_once_with(mcp_cli.MCP_DEPLOYMENT_QUESTION)
        run.named.assert_not_called()
        assert run.setup.call_args.args[0]["api_key"] is None

    def test_the_flag__self_hosted__connects_to_the_answers(self):
        connection = mcp_cli._resolve_setup_params(
            _config(
                api_key="answer-key", url_override="https://opik.acme.com/opik/api/"
            )
        )

        run = self._run(
            saved=_config(api_key="saved-key"),
            args=("--ignore-opik-config",),
            deployment=mcp_cli.interactive_helpers.DeploymentType.SELF_HOSTED,
            connection=connection,
        )

        assert run.result.exit_code == 0, run.result.output
        assert run.setup.call_args.args[0] == connection
        # Not repeated back: the user has just typed it.
        run.named.assert_not_called()

    def test_the_flag__checks_certificates_even_if_the_saved_opik_did_not(self):
        """The saved setting was for the saved Opik, not the one chosen now."""
        connection = mcp_cli._resolve_setup_params(
            _config(
                api_key="answer-key",
                url_override="https://opik.acme.com/opik/api/",
                check_tls_certificate=False,
            )
        )

        run = self._run(
            saved=_config(api_key="saved-key", check_tls_certificate=False),
            args=("--ignore-opik-config",),
            deployment=mcp_cli.interactive_helpers.DeploymentType.SELF_HOSTED,
            connection=connection,
        )

        assert run.result.exit_code == 0, run.result.output
        assert run.setup.call_args.args[0]["check_tls_certificate"] is True

    def test_the_flag__the_account_reported_is_the_one_connected_to(self):
        """Not the saved one: the run sets that aside."""
        connection = mcp_cli._resolve_setup_params(
            _config(
                api_key="answer-key", url_override="https://opik.acme.com/opik/api/"
            )
        )

        run = self._run(
            saved=_config(api_key="saved-key"),
            args=("--ignore-opik-config",),
            deployment=mcp_cli.interactive_helpers.DeploymentType.SELF_HOSTED,
            connection=connection,
        )

        entry, result = (call.args[0] for call in run.identity.call_args_list)
        assert entry.api_key is None
        assert result.api_key == "answer-key"
        assert result.url_override == "https://opik.acme.com/opik/api/"

    def test_the_flag__is_reported_on_both_events(self):
        run = self._run(
            saved=_config(api_key="key"),
            args=("--ignore-opik-config",),
            deployment=mcp_cli.interactive_helpers.DeploymentType.CLOUD,
        )

        assert [
            call.kwargs["ignore_opik_config"] for call in run.track.call_args_list
        ] == [
            True,
            True,
        ]

    def test_the_flag__without_a_terminal__says_it_needs_one(self):
        run = self._run(
            saved=_config(api_key="key"),
            args=("--ignore-opik-config", "--ai-client", "cursor"),
            interactive=False,
        )

        assert run.result.exit_code != 0
        assert "--ignore-opik-config" in run.result.output
        assert "terminal" in run.result.output
        run.setup.assert_not_called()


def test_help__describes_the_flag_that_sets_the_saved_opik_aside():
    result = CliRunner().invoke(cli, ["mcp", "configure", "--help"])

    assert result.exit_code == 0
    assert "--ignore-opik-config" in result.output
    assert "left unchanged" in result.output


class TestWhereTheSavedConnectionCameFrom:
    @pytest.fixture(autouse=True)
    def no_connection_variables(self, monkeypatch, tmp_path):
        for name in mcp_cli._CONNECTION_ENV_VARS:
            monkeypatch.delenv(name, raising=False)
        self.config_file = tmp_path / "opik.config"
        monkeypatch.setenv("OPIK_CONFIG_PATH", str(self.config_file))

    def test_the_file__by_its_path(self):
        self.config_file.write_text("[opik]\n")

        assert mcp_cli._saved_source(OpikConfig()) == str(self.config_file)

    def test_a_variable_that_overrides_it__is_named_too(self, monkeypatch):
        """Otherwise the user edits a file that changes nothing."""
        self.config_file.write_text("[opik]\n")
        monkeypatch.setenv("OPIK_URL_OVERRIDE", "http://localhost:5173/api/")

        assert (
            mcp_cli._saved_source(OpikConfig())
            == f"{self.config_file} and OPIK_URL_OVERRIDE"
        )

    def test_variables_alone(self, monkeypatch):
        monkeypatch.setenv("OPIK_API_KEY", "key")
        monkeypatch.setenv("OPIK_WORKSPACE", "acme-ai")

        assert mcp_cli._saved_source(OpikConfig()) == "OPIK_API_KEY and OPIK_WORKSPACE"


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
    """How the run ends, resolved before the result event so it can be reported."""

    @staticmethod
    def _result_event(
        registered,
        traced_project=None,
        can_launch=True,
        accepted=True,
        abort=False,
        interactive=True,
        args=(),
        lookup_interrupted=False,
        declined=None,
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
                mcp_cli.mcp_handoff,
                "traced_project",
                return_value=traced_project,
                side_effect=KeyboardInterrupt if lookup_interrupted else None,
            ),
            patch.object(
                mcp_cli.mcp_handoff,
                "launch_command",
                return_value=["/usr/bin/claude"] if can_launch else None,
            ),
            patch.object(mcp_cli.mcp_handoff, "launch"),
            patch(
                "click.confirm",
                side_effect=click.Abort if abort else None,
                return_value=accepted,
            ),
            patch.object(mcp_cli.install_view, "render_handoff"),
            patch.object(mcp_cli.install_view, "render_handoff_offer"),
            patch.object(
                mcp_cli.install_view, "render_handoff_declined", declined or Mock()
            ),
            patch.object(mcp_cli.install_view, "render_prompt_to_paste"),
            patch.object(mcp_cli.analytics, "track_event") as track,
        ):
            result = runner.invoke(cli, ["mcp", "configure", *args])
            assert result.exit_code == 0, result.output
        return track.call_args_list[-1].kwargs

    def test_ctrl_c_during_the_project_lookup__still_reports_the_run(self):
        """Ctrl-C during the silent lookup after the install still reports the run."""
        event = self._result_event(("claude-code",), lookup_interrupted=True)

        assert event["handoff"] == "interrupted"
        assert event["clients_written"] == 1

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
        """Declining is an outcome of its own, not a failure."""
        event = self._result_event(("claude-code",), accepted=False)

        assert event["handoff"] == "declined"
        # Still resolved, because the question the user turned down is the same
        # one a launch would have opened on.
        assert event["closing_prompt"] == "instrument"

    def test_ctrl_c_at_the_offer__ends_the_same_way_as_saying_no(self):
        """By then the server and pack are in; aborting would lose the result."""
        event = self._result_event(("claude-code",), abort=True)

        assert event["handoff"] == "declined"

    def test_ctrl_c_at_the_offer__the_ending_replaces_the_offer_line(self):
        """The terminal leaves the cursor after the `^C` on the offer's line."""
        declined = Mock()

        self._result_event(("claude-code",), abort=True, declined=declined)

        declined.assert_called_once_with("Claude Code", replace_offer=True)

    def test_saying_no_at_the_offer__the_ending_goes_below_it(self):
        declined = Mock()

        self._result_event(("claude-code",), accepted=False, declined=declined)

        declined.assert_called_once_with("Claude Code", replace_offer=False)

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
        """A cancel registers nothing, but is not `not_single_client`."""
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

    def test_sign_in_failed__ends_on_the_sign_in_note_alone(self):
        """The sign-in ending already said what to do; no "ask about your projects"."""
        runner = CliRunner()
        outcome = assistants.Outcome(
            clients=1,
            skills=True,
            registered_clients=("claude-code",),
            sign_in="failed",
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

        assert track.call_args_list[-1].kwargs["handoff"] == "sign_in_failed"
        restart.assert_not_called()


class TestResultEventCarriesTheConnectionSignals:
    """What the adoption board (dashboard 2057363) needs to join a run to its
    connection: the transport, and whether the sign-in worked."""

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
            patch.object(
                mcp_cli.mcp_handoff, "launch_command", return_value=["/usr/bin/claude"]
            ),
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
        """The hosted probe passes without a sign-in; `sign_in` is what shows it."""
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


def test_handoff__no_api_key__does_not_look_up_projects(monkeypatch):
    """Cloud signed in over OAuth leaves this machine nothing to look with."""
    lookups = []
    monkeypatch.setattr(mcp_cli.interactive_helpers, "is_interactive", lambda: True)
    monkeypatch.setattr(
        mcp_cli.mcp_targets,
        "find_target",
        lambda key: SimpleNamespace(display_name="Claude Code"),
    )
    monkeypatch.setattr(
        mcp_cli.mcp_handoff, "traced_project", lambda **kw: lookups.append(kw)
    )
    monkeypatch.setattr(mcp_cli.mcp_handoff, "launch_command", lambda key: None)
    outcome = assistants.Outcome(clients=1, skills=True, registered_clients=("cursor",))

    handoff = mcp_cli._resolve_handoff(mcp_cli._opik_cloud_params(), outcome)

    assert lookups == []
    # The agent can look once signed in, so it is asked to check first.
    assert handoff.prompt_kind == "check_first"
    assert handoff.prompt == mcp_cli.mcp_handoff.CHECK_FIRST_PROMPT
