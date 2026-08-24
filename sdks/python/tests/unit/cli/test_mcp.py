"""Tests for the ``opik mcp configure`` command."""

import pathlib
from unittest.mock import patch

import pytest

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
            patch.object(mcp_cli.mcp_installer, "setup_mcp_server") as setup_spy,
        ):
            result = runner.invoke(cli, ["mcp", "configure"])

        assert result.exit_code == 0
        setup_spy.assert_called_once()
        assert setup_spy.call_args.kwargs["api_key"] == "key"
        assert setup_spy.call_args.kwargs["workspace"] == "acme-ai"
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
            patch.object(mcp_cli.mcp_installer, "setup_mcp_server") as setup_spy,
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
            patch.object(mcp_cli.mcp_installer, "setup_mcp_server") as setup_spy,
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
            patch.object(mcp_cli.mcp_installer, "setup_mcp_server") as setup_spy,
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
            patch.object(mcp_cli.mcp_installer, "setup_mcp_server") as setup_spy,
        ):
            result = runner.invoke(cli, ["mcp", "configure"], input="y\n")

        assert result.exit_code == 0
        configure_spy.assert_called_once_with(install_mcp=False)
        setup_spy.assert_called_once()
        assert setup_spy.call_args.kwargs["api_key"] == "new-key"

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
        assert "configured for 1 AI assistant" in result.output
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
        assert "not configured for any AI assistant" in result.output
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
            patch.object(mcp_cli.mcp_installer, "setup_mcp_server") as setup_spy,
        ):
            result = runner.invoke(cli, ["mcp", "configure"])

        assert result.exit_code == 0
        setup_spy.assert_called_once()
        assert setup_spy.call_args.kwargs["use_local"] is True


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
            patch.object(mcp_cli.mcp_installer, "setup_mcp_server") as setup_spy,
        ):
            result = runner.invoke(cli, ["mcp", "configure", "--host", "codex"])

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
            patch.object(mcp_cli.mcp_installer, "setup_mcp_server") as setup_spy,
        ):
            result = runner.invoke(
                cli,
                ["mcp", "configure", "--host", "codex", "--host", "cursor"],
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
            patch.object(mcp_cli.mcp_installer, "setup_mcp_server") as setup_spy,
        ):
            result = runner.invoke(
                cli, ["mcp", "configure", "--host", "codex", "--host", "codex"]
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
            patch.object(mcp_cli.mcp_installer, "setup_mcp_server") as setup_spy,
        ):
            result = runner.invoke(cli, ["mcp", "configure"])

        assert result.exit_code == 0
        assert setup_spy.call_args.kwargs["host_keys"] is None

    def test_configure__unknown_host__is_rejected_by_the_parser(self):
        runner = CliRunner()
        result = runner.invoke(cli, ["mcp", "configure", "--host", "emacs"])

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
            patch.object(mcp_cli.mcp_installer, "setup_mcp_server") as setup_spy,
        ):
            result = runner.invoke(cli, ["mcp", "configure", "--host", "all"])

        assert result.exit_code == 0
        assert setup_spy.call_args.kwargs["host_keys"] == ["cursor", "codex"]

    def test_configure__host_all_with_nothing_detected__errors(self):
        runner = CliRunner()
        with (
            patch.object(mcp_cli.mcp_targets, "detected_targets", return_value=[]),
            patch.object(mcp_cli.mcp_installer, "setup_mcp_server") as setup_spy,
        ):
            result = runner.invoke(cli, ["mcp", "configure", "--host", "all"])

        assert result.exit_code != 0
        assert "no supported AI host" in result.output
        setup_spy.assert_not_called()

    def test_configure__non_interactive_with_host__succeeds(self):
        """The terminal was only ever needed to ask which host to use."""
        runner = CliRunner()
        with (
            patch.object(
                mcp_cli.opik_config, "OpikConfig", return_value=_config(api_key="key")
            ),
            patch.object(
                mcp_cli.interactive_helpers, "is_interactive", return_value=False
            ),
            patch.object(mcp_cli.mcp_installer, "setup_mcp_server") as setup_spy,
        ):
            result = runner.invoke(cli, ["mcp", "configure", "--host", "codex"])

        assert result.exit_code == 0
        setup_spy.assert_called_once()

    def test_configure__non_interactive_without_host__suggests_the_flag(self):
        runner = CliRunner()
        with (
            patch.object(
                mcp_cli.interactive_helpers, "is_interactive", return_value=False
            ),
            patch.object(mcp_cli.mcp_installer, "setup_mcp_server") as setup_spy,
        ):
            result = runner.invoke(cli, ["mcp", "configure"])

        assert result.exit_code != 0
        assert "--host" in result.output
        setup_spy.assert_not_called()

    def test_configure__non_interactive_host_but_unconfigured__errors_clearly(self):
        """We cannot run the interactive configure wizard without a terminal."""
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
            patch.object(mcp_cli.mcp_installer, "setup_mcp_server") as setup_spy,
        ):
            result = runner.invoke(cli, ["mcp", "configure", "--host", "codex"])

        assert result.exit_code != 0
        assert "OPIK_API_KEY" in result.output
        configure_spy.assert_not_called()
        setup_spy.assert_not_called()


class TestSkillsAsPartOfMcpConfigure:
    """`opik mcp configure` sets up both by default; the pack is opt-out."""

    def _patches(self, **overrides):
        base = dict(
            config=_config(api_key="key"),
            interactive=True,
            configured_hosts=["cursor"],
        )
        base.update(overrides)
        return base

    def test_configure__default__installs_the_server_and_the_pack(self):
        runner = CliRunner()
        with (
            patch.object(
                mcp_cli.opik_config, "OpikConfig", return_value=_config(api_key="key")
            ),
            patch.object(
                mcp_cli.interactive_helpers, "is_interactive", return_value=True
            ),
            patch.object(mcp_cli.selector, "is_supported", return_value=False),
            patch.object(
                mcp_cli.mcp_installer, "setup_mcp_server", return_value=["cursor"]
            ) as mcp_spy,
            patch.object(
                mcp_cli.skills_installer, "setup_skills", return_value=True
            ) as skills_spy,
        ):
            result = runner.invoke(cli, ["mcp", "configure", "--host", "cursor"])

        assert result.exit_code == 0
        mcp_spy.assert_called_once()
        skills_spy.assert_called_once_with(["cursor"])

    def test_configure__reuses_the_hosts_the_server_was_set_up_for(self):
        """The assistants question must not be asked twice in one run."""
        runner = CliRunner()
        with (
            patch.object(
                mcp_cli.opik_config, "OpikConfig", return_value=_config(api_key="key")
            ),
            patch.object(
                mcp_cli.interactive_helpers, "is_interactive", return_value=True
            ),
            patch.object(mcp_cli.selector, "is_supported", return_value=False),
            patch.object(
                mcp_cli.mcp_installer,
                "setup_mcp_server",
                return_value=["claude-code", "codex"],
            ),
            patch.object(mcp_cli.skills_cli, "resolve_hosts_interactively") as ask_spy,
            patch.object(
                mcp_cli.skills_installer, "setup_skills", return_value=True
            ) as skills_spy,
        ):
            result = runner.invoke(cli, ["mcp", "configure"])

        assert result.exit_code == 0
        skills_spy.assert_called_once_with(["claude-code", "codex"])
        ask_spy.assert_not_called()

    def test_configure__no_skills_flag__installs_only_the_server(self):
        runner = CliRunner()
        with (
            patch.object(
                mcp_cli.opik_config, "OpikConfig", return_value=_config(api_key="key")
            ),
            patch.object(
                mcp_cli.interactive_helpers, "is_interactive", return_value=True
            ),
            patch.object(
                mcp_cli.mcp_installer, "setup_mcp_server", return_value=["cursor"]
            ),
            patch.object(mcp_cli.skills_installer, "setup_skills") as skills_spy,
        ):
            result = runner.invoke(
                cli, ["mcp", "configure", "--host", "cursor", "--no-skills"]
            )

        assert result.exit_code == 0
        skills_spy.assert_not_called()

    def test_configure__plan_mentions_the_pack_when_it_is_included(self):
        runner = CliRunner()
        with (
            patch.object(
                mcp_cli.opik_config, "OpikConfig", return_value=_config(api_key="key")
            ),
            patch.object(
                mcp_cli.interactive_helpers, "is_interactive", return_value=True
            ),
            patch.object(mcp_cli.selector, "is_supported", return_value=False),
            patch.object(
                mcp_cli.mcp_installer, "setup_mcp_server", return_value=["cursor"]
            ) as mcp_spy,
            patch.object(mcp_cli.skills_installer, "setup_skills", return_value=True),
        ):
            runner.invoke(cli, ["mcp", "configure", "--host", "cursor"])

        assert mcp_spy.call_args.kwargs["plan_extras"] == ["and the Opik skill pack"]

    def test_configure__no_skills__plan_mentions_nothing_extra(self):
        runner = CliRunner()
        with (
            patch.object(
                mcp_cli.opik_config, "OpikConfig", return_value=_config(api_key="key")
            ),
            patch.object(
                mcp_cli.interactive_helpers, "is_interactive", return_value=True
            ),
            patch.object(
                mcp_cli.mcp_installer, "setup_mcp_server", return_value=["cursor"]
            ) as mcp_spy,
        ):
            runner.invoke(cli, ["mcp", "configure", "--host", "cursor", "--no-skills"])

        assert mcp_spy.call_args.kwargs["plan_extras"] == []

    def test_configure__server_installed_nothing__does_not_install_the_pack_blindly(
        self,
    ):
        """No successful host means no host to install a pack for."""
        runner = CliRunner()
        with (
            patch.object(
                mcp_cli.opik_config, "OpikConfig", return_value=_config(api_key="key")
            ),
            patch.object(
                mcp_cli.interactive_helpers, "is_interactive", return_value=True
            ),
            patch.object(mcp_cli.selector, "is_supported", return_value=False),
            patch.object(mcp_cli.mcp_installer, "setup_mcp_server", return_value=[]),
            patch.object(
                mcp_cli.skills_cli, "resolve_hosts_interactively", return_value=[]
            ),
            patch.object(mcp_cli.skills_installer, "setup_skills") as skills_spy,
        ):
            result = runner.invoke(cli, ["mcp", "configure"])

        assert result.exit_code == 0
        skills_spy.assert_not_called()


class TestComponentSelector:
    def test_choose_components__skills_flag_false__server_only(self):
        assert mcp_cli._choose_components(False) == [mcp_cli.COMPONENT_MCP]

    def test_choose_components__skills_flag_true__both_without_asking(self):
        assert mcp_cli._choose_components(True) == [
            mcp_cli.COMPONENT_MCP,
            mcp_cli.COMPONENT_SKILLS,
        ]

    def test_choose_components__no_picker_support__defaults_to_both(self):
        with patch.object(mcp_cli.selector, "is_supported", return_value=False):
            assert mcp_cli._choose_components(None) == [
                mcp_cli.COMPONENT_MCP,
                mcp_cli.COMPONENT_SKILLS,
            ]

    def test_choose_components__both_are_preselected(self):
        """ "mcp + skills" must be what Enter accepts."""
        with (
            patch.object(mcp_cli.selector, "is_supported", return_value=True),
            patch.object(
                mcp_cli.selector, "multiselect", return_value=["mcp"]
            ) as picker,
        ):
            mcp_cli._choose_components(None)

        assert picker.call_args.kwargs["preselected"] == [
            mcp_cli.COMPONENT_MCP,
            mcp_cli.COMPONENT_SKILLS,
        ]

    def test_choose_components__cancelled__aborts(self):
        with (
            patch.object(mcp_cli.selector, "is_supported", return_value=True),
            patch.object(mcp_cli.selector, "multiselect", return_value=None),
        ):
            with pytest.raises(Exception, match="Cancelled"):
                mcp_cli._choose_components(None)
