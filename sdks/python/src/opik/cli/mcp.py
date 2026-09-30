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
    run_configure(local_server=local_server, hosts=hosts, skills_flag=skills_flag)


@analytics.entry_point
def run_configure(
    local_server: bool = False,
    hosts: Tuple[str, ...] = (),
    skills_flag: Optional[bool] = None,
    invoked_via: str = "direct",
) -> None:
    """The `opik mcp configure` flow, callable without going through click.

    `opik configure` redirects into this when the user says yes to MCP, so that
    there is one MCP setup flow rather than two that drift: one picker, one
    sign-in, one ending inside the agent, and one funnel describing all of it.

    `@entry_point` is what keeps that last part true. Analytics drops an event
    reported from a function another `opik` module called, so on the redirect both
    events below would be suppressed and the flow would be measured only when
    typed directly - which is not the path most people take to it.
    """
    # Before the first event, so every event this flow reports carries it - the
    # result, and a failure once there is one to report. A run reached through
    # `opik configure` has already said yes to MCP and already has a working
    # config, so it converts differently from a cold `uvx opik mcp configure`;
    # pooling the two without being able to separate them moves the headline and
    # hides why. Recorded here rather than passed to each event because the two
    # populations have to stay separable further down the flow as well.
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

    if _needs_opik_configuration(params):
        if not interactive_helpers.is_interactive():
            raise click.ClickException(
                "Opik is not configured yet, and configuring it needs an "
                "interactive terminal. Set OPIK_API_KEY and OPIK_WORKSPACE, or run "
                "`opik configure`, then re-run this command."
            )
        if not install_view.confirm_default_yes(
            "Opik is not configured yet. Configure it now?"
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
    detected = mcp_targets.detected_targets()
    detected_clients = len(detected)

    # The banner, not `opik configure`'s "Opik MCP (Recommended)" block: that one
    # introduces a question this command has already been answered by being run,
    # and asking it again reads as a second chance to decline. A client named
    # with `--ai-client` gets neither — it is a scripted run — and neither does a
    # redirect from `opik configure`, which opened on the same logo a moment ago.
    if not host_keys and invoked_via == "direct":
        install_view.render_mcp_banner()
    # Installed rather than offered: the pack is what teaches the client to use
    # the server this command just registered, so a run that set one up without
    # the other did half the job. `--no-skills` is still honoured, because a
    # script saying no is a decision rather than an unanswered question.
    skills_verdict = consent.resolve_installed_by_default(skills_flag)

    outcome = assistants.setup(
        params,
        install_mcp=True,
        skills=skills_verdict,
        force_local_server=local_server,
        host_keys=host_keys,
    )

    # Resolved before the result event, not after the handoff is performed: the
    # launch branch replaces this process, so anything left unreported here would
    # never be reported at all.
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
        # How the run ends, resolved just above so that it can be reported at all:
        # the handoff replaces this process, so nothing after it would be said.
        # Which server was registered, so a run can be matched to what the MCP
        # server went on to report: the hosted one authenticates over OAuth and is
        # counted per Comet login, the local one by the API key digest both sides
        # already report. Without it neither join can be chosen.
        transport=outcome.transport or "",
        # The hosted server answers nothing at all until the client has signed in,
        # so this is the difference between a registration and a usable server —
        # and `verification_succeeded` cannot see it, being a reachability probe
        # against an endpoint that challenges everyone.
        sign_in=outcome.sign_in,
        # Ctrl-C at the picker, which is not the same answer as choosing no
        # client: one is "stop", the other is a decision about the server.
        cancelled=outcome.cancelled,
        # A stale uv tool install pins `uvx opik-mcp` at whatever was current when
        # it was left behind, and the versions still out there predate identity
        # resolution — so one we could not clear produces a server whose events
        # nothing can attribute.
        stale_tool=outcome.stale_tool,
        handoff=handoff.outcome,
        # Empty rather than absent when there was no handoff, so one property key
        # never carries a string on one event and nothing on another.
        closing_prompt=handoff.prompt_kind or "",
        # Resolved again, not reused: this command can run `opik configure` on the
        # way through, which is what turns an unconfigured run into an attributed
        # one.
        **account_identity.event_properties(),
    )

    _perform_handoff(handoff)


class _Handoff(NamedTuple):
    """How the run ends, decided before it is reported so it can be.

    The handoff is what the command is for, so a run that registered a server and
    then could not hand over is a different outcome from one that dropped the user
    into their agent — and neither was visible while this was decided after the
    result event.
    """

    #: `launch`, `declined`, `prompt_shown`, `no_terminal`, `not_single_client`,
    #: `sign_in_failed` or `cancelled`. `launch` and `declined` are the two
    #: answers to a question that is actually asked, so together they are the
    #: number of runs that got as far as being offered their first question —
    #: the last stage of the funnel, and the only one the user drives.
    outcome: str
    #: Whether this run wrote into an AI client's configuration. The closing
    #: "restart it" note is only true for a run that changed something, and the
    #: endings that reach it cover both — a cancel writes nothing, a scripted
    #: multi-client run writes several.
    wrote_config: bool = False
    #: `diagnose` or `instrument` — which says whether the workspace already had
    #: traces of the user's own, the one thing the closing prompt turns on.
    prompt_kind: Optional[str] = None
    host_key: Optional[str] = None
    display_name: Optional[str] = None
    prompt: Optional[str] = None


def _resolve_handoff(params: McpSetupParams, outcome: assistants.Outcome) -> _Handoff:
    """Settle how to end, including asking, without doing it yet.

    Registering a server is not the point — using it is. Which question depends
    on what the user has: traces of their own mean there is something to
    diagnose, and nothing logged yet means the next step is instrumenting an app
    rather than staring at an empty project.

    Only for a single registered client, which is what the picker now returns,
    and only with a terminal: `--ai-client` in a script is a request to
    configure, not to be replaced by an agent.
    """
    # First, because a cancelled run registers nothing and so would otherwise be
    # claimed by the client-count check below — which would file every Ctrl-C
    # under `not_single_client` and print a restart note for a run that wrote
    # nothing to restart for.
    if outcome.cancelled:
        return _Handoff(outcome="cancelled")

    wrote_config = bool(outcome.registered_clients)
    if not interactive_helpers.is_interactive():
        return _Handoff(outcome="no_terminal", wrote_config=wrote_config)
    if len(outcome.registered_clients) != 1:
        return _Handoff(outcome="not_single_client", wrote_config=wrote_config)
    if outcome.sign_in == "failed":
        # An unauthorized hosted server advertises no tools at all, so dropping
        # the user into their agent on a question it cannot answer would teach
        # them the integration is broken. The installer has already told them how
        # to finish the sign-in by hand.
        return _Handoff(outcome="sign_in_failed", wrote_config=True)

    host_key = outcome.registered_clients[0]
    target = mcp_targets.find_target(host_key)
    display_name = target.display_name if target is not None else host_key

    project = mcp_handoff.traced_project(
        api_key=params["api_key"],
        workspace=params["workspace"],
        api_url=params["api_url"],
        check_tls_certificate=params["check_tls_certificate"],
    )
    prompt_kind = "diagnose" if project is not None else "instrument"
    prompt = mcp_handoff.closing_prompt(project)

    if not mcp_handoff.can_launch(host_key):
        return _Handoff(
            outcome="prompt_shown",
            wrote_config=True,
            prompt_kind=prompt_kind,
            host_key=host_key,
            display_name=display_name,
            prompt=prompt,
        )

    # Asked rather than done. Starting the agent replaces this process, which is
    # a large enough thing to happen unannounced that it was worth a question —
    # and the answer is the one piece of this funnel that measures wanting to
    # use the server rather than having one installed.
    install_view.render_handoff_offer(prompt)
    try:
        accepted = install_view.confirm_default_yes(f"Try it in {display_name}?")
    except click.Abort:
        # Ctrl-C here is not a failed run: the server is registered and the pack
        # is installed. It means "not now", which is the same answer as `n`.
        accepted = False

    return _Handoff(
        outcome="launch" if accepted else "declined",
        wrote_config=True,
        prompt_kind=prompt_kind,
        host_key=host_key,
        display_name=display_name,
        prompt=prompt,
    )


def _perform_handoff(handoff: _Handoff) -> None:
    """End inside the agent, or leave the prompt where the user can reach it."""
    if handoff.host_key is None or handoff.prompt is None:
        # Nothing to hand over to and no one client to name. A run that still
        # wrote a configuration gets the generic instruction, because that
        # configuration is not read until the client restarts; a cancelled one
        # gets nothing, having changed nothing.
        if handoff.wrote_config:
            install_view.render_restart_note(mcp_installed=True)
        return

    # Set together with `host_key`, so this only ever falls back for a client the
    # target list does not know by name.
    display_name = handoff.display_name or handoff.host_key

    if handoff.outcome == "prompt_shown":
        install_view.render_prompt_to_paste(display_name, handoff.prompt)
        return

    if handoff.outcome == "declined":
        install_view.render_handoff_declined(display_name)
        return

    install_view.render_handoff(display_name)
    # `launch` replaces this process, so `atexit` never runs and anything still
    # queued would be lost. The events describing this run are the reason the
    # run happened.
    analytics.flush()
    mcp_handoff.launch(handoff.host_key, handoff.prompt)


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
