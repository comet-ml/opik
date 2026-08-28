"""`opik mcp` commands for managing the Opik MCP server integration."""

import logging
from typing import List, Optional, Tuple, TypedDict

import click

import opik.config as opik_config
import opik.url_helpers as url_helpers
from opik import analytics
from opik.cli import configure as configure_cli
from opik.cli import assistants
from opik.cli import install_view
from opik.cli import status_view
from opik.configurator import consent
from opik.configurator import interactive_helpers
from opik.configurator.mcp import status as mcp_status
from opik.configurator.mcp import targets as mcp_targets

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


HOST_ALL = "all"


def _resolve_host_keys(hosts: Tuple[str, ...]) -> Optional[List[str]]:
    """Turn ``--ai-client`` values into the concrete host keys to install for.

    ``None`` means "no client was named", which leaves detection and prompting to
    the installer. ``all`` expands to every client detected on this machine, so it
    stays a statement about this machine rather than a request to write configs
    for tools that are not installed.
    """
    if len(hosts) == 0:
        return None

    if HOST_ALL in hosts:
        detected = [target.key for target in mcp_targets.detected_targets()]
        if len(detected) == 0:
            raise click.ClickException(
                "`--ai-client all` found no supported AI client on this machine. Name one "
                f"explicitly instead: {', '.join(mcp_targets.HOST_KEYS)}."
            )
        return detected

    # De-duplicate while keeping the order the user typed.
    return list(dict.fromkeys(hosts))


@mcp.command(name="configure")
@click.option(
    "--local-server",
    is_flag=True,
    default=False,
    help="Install the local MCP server (run via uvx) instead of the Comet-hosted "
    "one, even when your deployment offers a hosted server.",
)
@click.option(
    "--ai-client",
    "hosts",
    multiple=True,
    type=click.Choice(mcp_targets.HOST_KEYS + [HOST_ALL], case_sensitive=False),
    help="AI client to register the server with. Repeatable, or pass `all` for "
    "every one detected on this machine. Naming a client is what lets this run "
    "without a terminal — a coding agent or a script should pass it.",
)
@click.option(
    "--skills/--no-skills",
    "skills_flag",
    default=None,
    help="Also install the Opik skill pack for the same clients. Default: yes. "
    "When omitted you are asked, with both pre-selected.",
)
def configure(
    local_server: bool, hosts: Tuple[str, ...], skills_flag: Optional[bool]
) -> None:
    """Register the Opik MCP server with your AI client(s).

    Reuses your existing Opik configuration (~/.opik.config), so run
    `opik configure` first if you have not configured Opik yet.

    Without a terminal — a coding agent, a script — name the client, which is what
    makes the request explicit:

        opik mcp configure --ai-client cursor --skills

    By default this uses the Comet-hosted MCP server when your deployment offers
    one, falling back to a local server otherwise. Pass `--local-server` to force
    the local server.
    """
    # Same reason as `opik configure`: the click frame is what makes this visible.
    analytics.track_event(
        "configuration",
        "mcp_configure",
        # Whether a client was named rather than picked: the agent-driven path.
        named_client=bool(hosts),
        client_count=len(hosts),
        # The tri-state as passed, so "asked for it" stays separable from "never
        # said". Named apart from the result event's boolean: one property key
        # must not carry a string on one event and a bool on another.
        skills_requested=str(skills_flag),
        local_server=local_server,
    )

    host_keys = _resolve_host_keys(hosts)
    # Without a terminal we cannot ask which client to write to, so one has to be
    # named. That is also what separates a coding agent running this for the user
    # from a CI job that was never asked to: the agent can pass the flag.
    if host_keys is None and not interactive_helpers.is_interactive():
        raise click.ClickException(
            "`opik mcp configure` needs either a terminal or an explicit client, "
            "because it writes into that client's own configuration. Name one to "
            "run unattended:\n\n"
            f"    opik mcp configure --ai-client {mcp_targets.HOST_KEYS[0]}\n\n"
            f"Valid values: {', '.join(mcp_targets.HOST_KEYS)}, all."
        )

    params = _resolve_setup_params(opik_config.OpikConfig())

    if _needs_opik_configuration(params):
        if not interactive_helpers.is_interactive():
            raise click.ClickException(
                "Opik is not configured yet, and configuring it needs an "
                "interactive terminal. Set OPIK_API_KEY and OPIK_WORKSPACE, or run "
                "`opik configure`, then re-run this command."
            )
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

    # Running this command *is* the consent for the server — that is what the
    # command does — so only the skill pack is still a question here.
    skills_verdict = consent.resolve(
        skills_flag,
        # No `-y` on this command, and nothing to detect-or-not: a named client
        # counts as something to install into even when it was not auto-detected.
        assume_yes=False,
        interactive=interactive_helpers.is_interactive(),
        anything_detected=bool(host_keys) or len(mcp_targets.detected_targets()) > 0,
    )
    if skills_verdict.reason is consent.Reason.NO_TERMINAL:
        install_view.console.print(
            "  Skipping the Opik skill pack: no terminal to ask in. Pass --skills "
            "to install it without being asked.",
            style="yellow",
        )

    outcome = assistants.setup(
        params,
        install_mcp=True,
        skills=skills_verdict,
        force_local_server=local_server,
        host_keys=host_keys,
    )

    # A sibling of the entry event, not a nested one: reporting is suppressed
    # inside an already-reporting stack, but two calls from this same frame both
    # survive. Entry says what was asked for, this says what happened — the pair
    # is what makes a drop-off visible.
    analytics.track_event(
        "configuration",
        "mcp_configure",
        "result",
        clients_written=outcome.clients,
        skills_installed=outcome.skills,
    )


@mcp.command(name="status")
def status() -> None:
    """Show which AI clients the Opik MCP server is configured for.

    Each AI client keeps its own MCP config, written at install time and not
    kept in sync with ~/.opik.config afterwards. This lists every AI client that
    has the Opik MCP server set up, what it points at, and whether that still
    matches your Opik configuration.
    """
    config = opik_config.OpikConfig()
    host_statuses = mcp_status.collect_host_statuses(config)
    status_view.render_mcp_status(config, host_statuses)
