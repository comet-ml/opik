"""`opik mcp` commands for managing the Opik MCP server integration."""

import logging
from typing import Optional, TypedDict

import click

import opik.config as opik_config
import opik.url_helpers as url_helpers
from opik.cli import configure as configure_cli
from opik.cli import status_view
from opik.configurator import interactive_helpers
from opik.configurator import mcp as mcp_installer
from opik.configurator.mcp import status as mcp_status

LOGGER = logging.getLogger(__name__)


class McpSetupParams(TypedDict):
    """Keyword arguments for ``setup_mcp_server`` derived from an ``OpikConfig``."""

    api_key: Optional[str]
    workspace: str
    base_url: str
    api_url: str
    use_local: bool
    self_hosted_comet: bool
    check_tls_certificate: bool


def _needs_opik_configuration(params: McpSetupParams) -> bool:
    """True when there is nothing usable to build a cloud/self-hosted MCP env.

    Local deployments need no API key, so they are always installable. A
    cloud/self-hosted target with no API key means Opik is not configured yet.
    """
    return not params["use_local"] and params["api_key"] is None


def _resolve_setup_params(config: opik_config.OpikConfig) -> McpSetupParams:
    """Map a loaded ``OpikConfig`` to ``setup_mcp_server`` keyword arguments.

    ``url_override`` is the full Opik REST base (``…/opik/api/`` for the Comet
    platform, ``…/api/`` for open-source Opik). We use that path to tell a
    self-hosted Comet platform apart from an open-source deployment, which the
    config file does not record explicitly.
    """
    api_url = config.url_override
    is_comet_platform = "/opik/api" in api_url

    use_local = config.is_localhost_installation or (
        not is_comet_platform and not config.is_cloud_installation
    )
    self_hosted_comet = is_comet_platform and not config.is_cloud_installation

    return {
        "api_key": config.api_key,
        "workspace": config.workspace,
        "base_url": url_helpers.get_base_url(api_url),
        "api_url": api_url,
        "use_local": use_local,
        "self_hosted_comet": self_hosted_comet,
        "check_tls_certificate": config.check_tls_certificate,
    }


@click.group(name="mcp")
def mcp() -> None:
    """Manage the Opik MCP server integration."""


@mcp.command(name="configure")
@click.option(
    "--local-server",
    is_flag=True,
    default=False,
    help="Install the local MCP server (run via uvx) instead of the Comet-hosted "
    "one, even when your deployment offers a hosted server.",
)
def configure(local_server: bool) -> None:
    """Register the Opik MCP server with your AI assistant(s).

    Reuses your existing Opik configuration (~/.opik.config), so run
    `opik configure` first if you have not configured Opik yet.

    By default this uses the Comet-hosted MCP server when your deployment offers
    one, falling back to a local server otherwise. Pass `--local-server` to force
    the local server.
    """
    if not interactive_helpers.is_interactive():
        raise click.ClickException(
            "`opik mcp configure` needs an interactive terminal to pick your AI host."
        )

    params = _resolve_setup_params(opik_config.OpikConfig())

    if _needs_opik_configuration(params):
        if not click.confirm(
            "Opik is not configured yet. Configure it now?", default=True
        ):
            raise click.ClickException(
                "Run `opik configure` first, then `opik mcp configure`."
            )
        # Skip configure's own MCP prompt — we install right after.
        configure_cli.run_interactive_configure(install_mcp=False)
        params = _resolve_setup_params(opik_config.OpikConfig())

        if _needs_opik_configuration(params):
            raise click.ClickException(
                "Opik configuration is still incomplete; aborting MCP install."
            )

    mcp_installer.setup_mcp_server(**params, force_local_server=local_server)


@mcp.command(name="status")
def status() -> None:
    """Show which AI assistants the Opik MCP server is configured for.

    Each AI assistant keeps its own MCP config, written at install time and not
    kept in sync with ~/.opik.config afterwards. This lists every assistant that
    has the Opik MCP server set up, what it points at, and whether that still
    matches your Opik configuration.
    """
    config = opik_config.OpikConfig()
    host_statuses = mcp_status.collect_host_statuses(config)
    status_view.render_mcp_status(config, host_statuses)
