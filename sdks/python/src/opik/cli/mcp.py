"""`opik mcp` commands for managing the Opik MCP server integration."""

import logging
from typing import List, NamedTuple, Optional, Tuple, TypedDict

import click

import opik.config as opik_config
import opik.environment_details as environment_details
import opik.url_helpers as url_helpers
from opik import analytics
from opik.cli import account_identity
from opik.cli import configure as configure_cli
from opik.cli import assistants
from opik.cli import install_view
from opik.cli import status_view
from opik.configurator import consent
from opik.configurator import interactive_helpers
from opik.configurator.mcp import handoff as mcp_handoff
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


#: The deployment question as `opik mcp configure` asks it: here the answer is
#: what the AI client connects to, not where traces go.
MCP_DEPLOYMENT_QUESTION = "Which Opik should your AI client connect to?"


def _opik_cloud_params() -> McpSetupParams:
    """Opik Cloud with no API key: the hosted server signs in with OAuth instead."""
    return {
        "api_key": None,
        "workspace": opik_config.OPIK_WORKSPACE_DEFAULT_NAME,
        "base_url": url_helpers.get_base_url(opik_config.OPIK_URL_CLOUD),
        "api_url": opik_config.OPIK_URL_CLOUD,
        "use_local": False,
        "self_hosted_comet": False,
        "check_tls_certificate": opik_config.OpikConfig().check_tls_certificate,
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
    help="Also install the Opik skill pack for the same clients. On by default; "
    "pass --no-skills to register the server without it.",
)
def configure(
    local_server: bool, hosts: Tuple[str, ...], skills_flag: Optional[bool]
) -> None:
    """Register the Opik MCP server with your AI client(s).

    Runs without the SDK installed: `uvx opik mcp configure`. Reuses your
    existing Opik configuration (~/.opik.config) and offers to create one if
    none exists.

    Without a terminal — a coding agent, a script — name the client, which is what
    makes the request explicit:

        opik mcp configure --ai-client cursor

    By default this uses the Comet-hosted MCP server when your deployment offers
    one, falling back to a local server otherwise. Pass `--local-server` to force
    the local server.
    """
    run_configure(
        local_server=local_server,
        hosts=hosts,
        skills_flag=skills_flag,
        invoked_via="direct",
    )


@analytics.entry_point
def run_configure(
    local_server: bool,
    hosts: Tuple[str, ...],
    skills_flag: Optional[bool],
    invoked_via: str,
) -> None:
    """The `opik mcp configure` flow, also entered from `opik configure`.

    `@entry_point` keeps its events reported on that redirect, where analytics
    would otherwise drop them as a nested SDK call.
    """
    # Before the first event, so every event of this run carries how it was
    # entered: a redirect from `opik configure` converts differently from a cold
    # run, and the funnel has to tell them apart.
    environment_details.set_run_context(invoked_via=invoked_via)

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
        # This command reuses an existing Opik configuration, so the account is
        # normally known from the start — this is the MCP funnel's entry point.
        **account_identity.event_properties(),
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
    detected = mcp_targets.detected_targets()
    detected_clients = len(detected)

    # A scripted run (`--ai-client`) gets no banner, and a redirect already
    # showed the logo.
    if not host_keys and invoked_via == "direct":
        install_view.render_mcp_banner()

    if _needs_opik_configuration(params):
        if not interactive_helpers.is_interactive():
            raise click.ClickException(
                "Opik is not configured yet, and configuring it needs an "
                "interactive terminal. Set OPIK_API_KEY and OPIK_WORKSPACE, or run "
                "`opik configure`, then re-run this command."
            )
        # No config yet: where Opik is first, so the client picker below stays
        # the step after it, as the funnel orders them. Cloud needs no API key —
        # the hosted server signs in with OAuth — so it writes no config;
        # self-hosted and local go through `opik configure`'s questions.
        deployment = configure_cli.ask_for_deployment_type(MCP_DEPLOYMENT_QUESTION)
        if deployment is interactive_helpers.DeploymentType.CLOUD:
            params = _opik_cloud_params()
        else:
            configure_cli.run_interactive_configure(
                install_mcp=False, deployment=deployment
            )
            params = _resolve_setup_params(opik_config.OpikConfig())
            if _needs_opik_configuration(params):
                raise click.ClickException(
                    "Opik configuration is still incomplete; aborting MCP install."
                )

    # Installed unless refused: the pack is what teaches the client to use the
    # server just registered.
    skills_verdict = consent.resolve_installed_by_default(skills_flag)

    outcome = assistants.setup(
        params,
        install_mcp=True,
        skills=skills_verdict,
        force_local_server=local_server,
        host_keys=host_keys,
    )

    # Before the result event: the launch branch replaces this process.
    handoff = _resolve_handoff(params, outcome)

    # A sibling of the entry event, not a nested one: reporting is suppressed
    # inside an already-reporting stack, but two calls from this same frame both
    # survive. Entry says what was asked for, this says what happened — the pair
    # is what makes a drop-off visible.
    analytics.track_event(
        "configuration",
        "mcp_configure",
        "result",
        clients_written=outcome.clients,
        clients_failed=outcome.failed_clients,
        skills_installed=outcome.skills,
        # The same properties that make a zero readable on `opik configure`, in
        # the same vocabulary so one query reads both. Here running the command
        # *is* the consent for the server, so `mcp_decision` is always a request
        # and a zero is never a refusal — which is what makes this the control
        # group. The pack is still a real question, so its answer is reported.
        detected_clients=detected_clients,
        mcp_decision=consent.Reason.REQUESTED.value,
        skills_decision=outcome.skills_decision,
        verification_succeeded=outcome.verified,
        # Same shape as `opik configure`, so one query counts client popularity
        # across both commands.
        clients_detected=",".join(sorted(target.key for target in detected)),
        clients_registered=",".join(sorted(outcome.registered_clients)),
        # `mcp_decision` is always a request here — running the command is the
        # permission — so this is the only thing that can say a run still wrote
        # nothing because the user chose no client in the picker.
        picker_skipped=outcome.mcp_declined,
        interactive=interactive_helpers.is_interactive(),
        # Which server was registered picks the join to the MCP server's own
        # events: the Comet login for hosted, the API key digest for local.
        transport=outcome.transport or "",
        # `verification_succeeded` cannot see this: the hosted probe passes
        # without a sign-in, but the server serves no tools until there is one.
        sign_in=outcome.sign_in,
        # Ctrl-C at the picker: "stop", not "no client".
        cancelled=outcome.cancelled,
        # A stale uv tool install pins an old opik-mcp that cannot be attributed.
        stale_tool=outcome.stale_tool,
        handoff=handoff.outcome,
        # Empty rather than absent, so the property always carries a string.
        closing_prompt=handoff.prompt_kind or "",
        # Resolved again, not reused: this command can run `opik configure` on the
        # way through, which is what turns an unconfigured run into an attributed
        # one.
        **account_identity.event_properties(),
    )

    _perform_handoff(handoff)


class _Handoff(NamedTuple):
    """How the run ends, decided before the result event so it can be reported."""

    #: `launch`, `declined`, `prompt_shown`, `no_terminal`, `not_single_client`,
    #: `sign_in_failed`, `cancelled` (at the picker, nothing written) or
    #: `interrupted` (after writing, before the offer). `launch` + `declined` are
    #: the runs that were offered the first question.
    outcome: str
    #: Whether an AI client's config was written, i.e. whether a restart is due.
    wrote_config: bool = False
    #: `diagnose` if the workspace has traces of the user's own, else `instrument`.
    prompt_kind: Optional[str] = None
    display_name: Optional[str] = None
    prompt: Optional[str] = None
    #: How to start the client with the prompt; None for one that cannot be.
    command: Optional[List[str]] = None


def _resolve_handoff(params: McpSetupParams, outcome: assistants.Outcome) -> _Handoff:
    """Decide how to end, asking if needed, without doing it yet.

    Only a single registered client in a terminal is offered the handoff:
    `--ai-client` in a script asks to configure, not to be replaced by an agent.
    """
    # First: a cancel registers nothing, and the count check below would
    # otherwise file it as `not_single_client`.
    if outcome.cancelled:
        return _Handoff(outcome="cancelled")

    wrote_config = bool(outcome.registered_clients)
    if not interactive_helpers.is_interactive():
        return _Handoff(outcome="no_terminal", wrote_config=wrote_config)
    if len(outcome.registered_clients) != 1:
        return _Handoff(outcome="not_single_client", wrote_config=wrote_config)
    if outcome.sign_in == "failed":
        # Without a sign-in the server has no tools, so the agent could not
        # answer; the installer has already said how to finish signing in.
        return _Handoff(outcome="sign_in_failed", wrote_config=True)

    host_key = outcome.registered_clients[0]
    target = mcp_targets.find_target(host_key)
    display_name = target.display_name if target is not None else host_key

    try:
        # With no key (Cloud signed in over OAuth) there is nothing to look the
        # workspace up with, so the instrument prompt it is.
        project = (
            mcp_handoff.traced_project(
                api_key=params["api_key"],
                workspace=params["workspace"],
                api_url=params["api_url"],
            )
            if params["api_key"] or params["use_local"]
            else None
        )
    except KeyboardInterrupt:
        # This runs silently after "Done", where Ctrl-C is likely; the run is
        # still reported, since the config was written.
        return _Handoff(outcome="interrupted", wrote_config=True)
    prompt_kind = "diagnose" if project is not None else "instrument"
    prompt = mcp_handoff.closing_prompt(project)

    command = mcp_handoff.launch_command(host_key)
    if command is None:
        return _Handoff(
            outcome="prompt_shown",
            wrote_config=True,
            prompt_kind=prompt_kind,
            display_name=display_name,
            prompt=prompt,
        )

    # Asked, because starting the agent replaces this process.
    install_view.render_handoff_offer(prompt)
    try:
        accepted = install_view.confirm_default_yes(f"Continue in {display_name}")
    except click.Abort:
        # Ctrl-C here means "not now", the same as `n`.
        accepted = False

    return _Handoff(
        outcome="launch" if accepted else "declined",
        wrote_config=True,
        prompt_kind=prompt_kind,
        display_name=display_name,
        prompt=prompt,
        command=command,
    )


def _perform_handoff(handoff: _Handoff) -> None:
    """End inside the agent, or leave the prompt where the user can reach it."""
    if handoff.display_name is None or handoff.prompt is None:
        # No one client to hand over to: tell a run that wrote config to restart
        # its client, unless the sign-in ending already said what is left.
        if handoff.wrote_config and handoff.outcome != "sign_in_failed":
            install_view.render_restart_note(mcp_installed=True)
        return

    if handoff.command is None:
        install_view.render_prompt_to_paste(handoff.display_name, handoff.prompt)
        return

    if handoff.outcome == "declined":
        install_view.render_handoff_declined(handoff.display_name)
        return

    install_view.render_handoff(handoff.display_name)
    # `launch` replaces this process, so `atexit` never runs.
    analytics.flush()
    mcp_handoff.launch(handoff.command, handoff.prompt)


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
