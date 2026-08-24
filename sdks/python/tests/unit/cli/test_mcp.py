"""Tests for the ``opik mcp configure`` command."""

import pathlib
from unittest.mock import patch


from click.testing import CliRunner

from opik.cli import cli
from opik.cli import mcp as mcp_cli
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
        assert setup_spy.call_args.kwargs["setup_params"]["api_key"] == "key"
        assert setup_spy.call_args.kwargs["setup_params"]["workspace"] == "acme-ai"
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
        assert "interactive terminal" in result.output
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
        assert setup_spy.call_args.kwargs["setup_params"]["api_key"] == "new-key"

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
        assert setup_spy.call_args.kwargs["setup_params"]["use_local"] is True


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

        assert result.exit_code != 0
        assert "interactive terminal" in result.output
        setup_spy.assert_not_called()

    def test_configure__non_interactive_without_host__refuses(self):
        runner = CliRunner()
        with (
            patch.object(
                mcp_cli.interactive_helpers, "is_interactive", return_value=False
            ),
            patch.object(mcp_cli.assistants, "setup") as setup_spy,
        ):
            result = runner.invoke(cli, ["mcp", "configure"])

        assert result.exit_code != 0
        assert "interactive terminal" in result.output
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
        # The terminal gate fires before Opik's own configuration is considered,
        # so this is the message even with credentials missing.
        assert "interactive terminal" in result.output
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
        assert kwargs["skills_flag"] is False
        assert kwargs["setup_params"]["api_key"] == "key"

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
