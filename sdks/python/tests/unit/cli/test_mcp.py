"""Tests for the ``opik mcp configure`` command."""

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
            patch.object(mcp_cli, "OpikConfig", return_value=_config(api_key="key")),
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
            patch.object(mcp_cli, "OpikConfig", return_value=_config(api_key=None)),
            patch.object(
                mcp_cli.interactive_helpers, "is_interactive", return_value=True
            ),
            patch.object(mcp_cli, "run_interactive_configure") as configure_spy,
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
            patch.object(mcp_cli, "OpikConfig", side_effect=lambda: next(configs)),
            patch.object(
                mcp_cli.interactive_helpers, "is_interactive", return_value=True
            ),
            patch.object(mcp_cli, "run_interactive_configure") as configure_spy,
            patch.object(mcp_cli.mcp_installer, "setup_mcp_server") as setup_spy,
        ):
            result = runner.invoke(cli, ["mcp", "configure"], input="y\n")

        assert result.exit_code == 0
        configure_spy.assert_called_once_with(install_mcp=False)
        setup_spy.assert_called_once()
        assert setup_spy.call_args.kwargs["api_key"] == "new-key"

    def test_install__local_without_api_key__proceeds(self):
        runner = CliRunner()
        with (
            patch.object(
                mcp_cli,
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
