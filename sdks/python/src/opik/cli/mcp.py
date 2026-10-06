"""`opik mcp` commands for managing the Opik MCP server integration."""

import logging
import os
import urllib.parse
from typing import Dict, List, NamedTuple, Optional, Tuple, TypedDict, cast

import click
import pydantic

import opik.config as opik_config
import opik.environment_details as environment_details
import opik.url_helpers as url_helpers
from opik import analytics
from opik.cli import account_identity
from opik.cli import configure as configure_cli
from opik.cli import assistants
from opik.cli import install_view
from opik.cli import status_view
from opik.configurator import configure as opik_configure
from opik.configurator import consent
from opik.configurator import interactive_helpers
from opik.configurator import opik_rest_helpers
from opik.configurator.mcp import handoff as mcp_handoff
from opik.configurator.mcp import spec as mcp_spec
from opik.configurator.mcp import view as mcp_view
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


#: `--deployment` values, in the order the deployment question lists them.
_DEPLOYMENT_FLAG_VALUES: Dict[str, interactive_helpers.DeploymentType] = {
    "cloud": interactive_helpers.DeploymentType.CLOUD,
    "self-hosted": interactive_helpers.DeploymentType.SELF_HOSTED,
    "local": interactive_helpers.DeploymentType.LOCAL,
}


def _check_connection_flags(
    deployment: Optional[interactive_helpers.DeploymentType],
    url: Optional[str],
    workspace: Optional[str],
) -> None:
    """Refuse a flag combination before the run starts, so a typo is a usage
    error rather than a failed run in the funnel."""
    if deployment is None:
        if url or workspace:
            raise click.UsageError(
                "--url and --workspace describe the Opik that --deployment names; "
                "pass --deployment local or --deployment self-hosted with them."
            )
        return
    if url is not None:
        parsed = urllib.parse.urlsplit(url)
        if parsed.scheme not in ("http", "https") or not parsed.hostname:
            raise click.UsageError(
                "--url needs a full address, such as http://localhost:5173 or "
                "https://comet.example.com."
            )
        # It would be written into the AI client's config and printed back.
        if parsed.username is not None or parsed.password is not None:
            raise click.UsageError(
                "--url must not carry credentials; the API key comes from OPIK_API_KEY."
            )
    if deployment is interactive_helpers.DeploymentType.CLOUD and (url or workspace):
        # The workspace is the one the user signs in to.
        raise click.UsageError("--deployment cloud takes no --url or --workspace.")
    if deployment is interactive_helpers.DeploymentType.LOCAL and workspace:
        raise click.UsageError(
            "--deployment local takes no --workspace: a local Opik has one, "
            f"`{opik_config.OPIK_WORKSPACE_DEFAULT_NAME}`."
        )
    if deployment is interactive_helpers.DeploymentType.SELF_HOSTED:
        if not url:
            raise click.UsageError(
                "--deployment self-hosted needs --url, the address of your Comet "
                "platform."
            )
        if not workspace:
            # Not guessed: an account with several would be read from the wrong one.
            raise click.UsageError(
                "--deployment self-hosted needs --workspace, or OPIK_WORKSPACE: the "
                "segment after /opik/ in your Opik URL."
            )


def _check_tls_for_a_named_opik() -> bool:
    """Whether to check certificates for an Opik named on the command line.

    A saved "don't check" was made for the saved Opik, and this run may send its
    API key to another one, so only the environment can turn the check off.
    """
    value = os.environ.get("OPIK_CHECK_TLS_CERTIFICATE", "").strip()
    if not value:
        return True
    try:
        return pydantic.TypeAdapter(bool).validate_python(value)
    except pydantic.ValidationError:
        return True


def _local_params(url: str, check_tls_certificate: bool) -> McpSetupParams:
    """An Opik run by the user (open source): no API key, one workspace."""
    base_url = url_helpers.get_base_url(url)
    return {
        "api_key": None,
        "workspace": opik_config.OPIK_WORKSPACE_DEFAULT_NAME,
        "base_url": base_url,
        "api_url": urllib.parse.urljoin(base_url, "api/"),
        "use_local": True,
        "self_hosted_comet": False,
        "check_tls_certificate": check_tls_certificate,
    }


def _self_hosted_params(
    url: str, workspace: str, check_tls_certificate: bool
) -> McpSetupParams:
    """A self-hosted Comet platform, checked before anything is written.

    The API key comes from ``OPIK_API_KEY`` only: a flag would leave it in the
    shell history and in a coding agent's transcript.
    """
    api_key = os.environ.get("OPIK_API_KEY", "").strip()
    if not api_key:
        raise click.ClickException(
            "--deployment self-hosted reads the API key from OPIK_API_KEY, which is "
            "not set. Set it in the environment and re-run."
        )
    base_url = url_helpers.get_base_url(url)

    try:
        if not opik_rest_helpers.is_api_key_correct(
            api_key, url=base_url, check_tls_certificate=check_tls_certificate
        ):
            raise click.ClickException(f"OPIK_API_KEY is not valid on {base_url}.")
        if not opik_rest_helpers.is_workspace_name_correct(
            api_key=api_key,
            workspace=workspace,
            url=base_url,
            check_tls_certificate=check_tls_certificate,
        ):
            raise click.ClickException(
                f"This API key has no workspace `{workspace}` on {base_url}."
            )
    except ConnectionError as error:
        raise click.ClickException(
            f"Could not check the API key on {base_url}: {error}"
        ) from error

    return {
        "api_key": api_key,
        "workspace": workspace,
        "base_url": base_url,
        "api_url": urllib.parse.urljoin(base_url, "opik/api/"),
        "use_local": False,
        "self_hosted_comet": True,
        "check_tls_certificate": check_tls_certificate,
    }


def _flag_params(
    deployment: interactive_helpers.DeploymentType,
    url: Optional[str],
    workspace: Optional[str],
) -> McpSetupParams:
    """The connection the flags name, checked before anything is written.

    Asks nothing, so it is how a run without a terminal says which Opik to use.
    ``configure`` has already refused flags that do not go together.
    """
    check_tls_certificate = _check_tls_for_a_named_opik()
    if deployment is interactive_helpers.DeploymentType.CLOUD:
        params = _opik_cloud_params()
        params["check_tls_certificate"] = check_tls_certificate
        return params
    if deployment is interactive_helpers.DeploymentType.LOCAL:
        params = _local_params(
            url or opik_configure.OPIK_BASE_URL_LOCAL,
            check_tls_certificate=check_tls_certificate,
        )
        # Checked now: the AI client would only find out after the restart.
        if not opik_rest_helpers.is_instance_active(
            params["base_url"], check_tls_certificate=check_tls_certificate
        ):
            raise click.ClickException(
                f"No Opik answers at {params['base_url']}. Start it, or pass the "
                "address it runs at: --url <url>."
            )
        return params
    assert url is not None and workspace is not None  # checked by `configure`
    return _self_hosted_params(
        url, workspace, check_tls_certificate=check_tls_certificate
    )


def _deployment_commands(hosts: Tuple[str, ...]) -> str:
    """The three ways to name an Opik without a terminal, for an error to end on."""
    clients = " ".join(f"--ai-client {host}" for host in hosts)
    return (
        f"    opik mcp configure {clients} --deployment cloud\n"
        f"    opik mcp configure {clients} --deployment local --url <url>\n"
        f"    opik mcp configure {clients} --deployment self-hosted --url <url> "
        "--workspace <workspace>\n\n"
        "Opik Cloud needs no API key: the AI client signs in in the browser. "
        "Self-hosted reads the key from OPIK_API_KEY."
    )


def _deployment_name(deployment: Optional[interactive_helpers.DeploymentType]) -> str:
    """For analytics, in `opik configure`'s vocabulary; empty without the flag."""
    return "" if deployment is None else deployment.name.lower()


def _names_an_opik(saved: opik_config.OpikConfig) -> bool:
    """Whether anything on this machine says which Opik, key or no key."""
    return saved.config_file_exists or bool(
        os.environ.get("OPIK_URL_OVERRIDE", "").strip()
    )


#: The variables that take precedence over ~/.opik.config for which Opik to use.
_CONNECTION_ENV_VARS = ("OPIK_URL_OVERRIDE", "OPIK_API_KEY", "OPIK_WORKSPACE")


def _saved_source(config: opik_config.OpikConfig) -> str:
    """Where the saved settings came from, named the way the user would look.

    A variable left set by a dev setup wins over the file, and saying "from
    ~/.opik.config" then would send the user to edit a file that changes nothing.
    """
    sources = [name for name in _CONNECTION_ENV_VARS if os.environ.get(name)]
    if config.config_file_exists:
        sources.insert(0, str(config.config_file_fullpath))
    if not sources:
        # Set some other way, a lower-case variable say.
        return "your Opik configuration"
    if len(sources) == 1:
        return sources[0]
    return f"{', '.join(sources[:-1])} and {sources[-1]}"


def _identity(params: McpSetupParams) -> Dict[str, analytics.PropertyValue]:
    """The account to report: the Opik this run connects to, which since this
    command writes no config file need not be the saved one."""
    return account_identity.event_properties(
        opik_config.OpikConfig(
            api_key=params["api_key"],
            url_override=params["api_url"],
            workspace=params["workspace"],
        )
    )


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
@click.option(
    "--ignore-opik-config",
    is_flag=True,
    default=False,
    help="Ask which Opik and workspace to connect to instead of using the ones "
    "saved in ~/.opik.config, which is left unchanged.",
)
@click.option(
    "--deployment",
    type=click.Choice(list(_DEPLOYMENT_FLAG_VALUES), case_sensitive=False),
    default=None,
    help="Which Opik to connect to, instead of the saved one or the question: "
    "`cloud` (the hosted server; no API key, the AI client signs in in the "
    "browser), `local` (an Opik you run yourself, at http://localhost:5173 unless "
    "--url says otherwise) or `self-hosted` (a Comet platform at --url, with the "
    "API key from OPIK_API_KEY).",
)
@click.option(
    "--url",
    default=None,
    help="Where Opik runs, for --deployment local or self-hosted.",
)
@click.option(
    "--workspace",
    default=None,
    help="The workspace, for --deployment self-hosted: the segment after /opik/ "
    "in your Opik URL. Defaults to OPIK_WORKSPACE.",
)
def configure(
    local_server: bool,
    hosts: Tuple[str, ...],
    skills_flag: Optional[bool],
    ignore_opik_config: bool,
    deployment: Optional[str],
    url: Optional[str],
    workspace: Optional[str],
) -> None:
    """Register the Opik MCP server with your AI client(s).

    Runs without the SDK installed: `uvx opik mcp configure`. Uses your Opik
    configuration (~/.opik.config) when there is one and asks which Opik to
    connect to when there is none; it never changes that file. To be asked
    anyway, and connect to a different Opik or workspace than the saved one:

        opik mcp configure --ignore-opik-config

    Without a terminal — a coding agent, a script — name the client, which is what
    makes the request explicit:

        opik mcp configure --ai-client cursor

    With no saved configuration it then uses a local Opik if one answers at
    http://localhost:5173, and otherwise needs `--deployment` to say which Opik:

        opik mcp configure --ai-client cursor --deployment cloud

    By default this uses the Comet-hosted MCP server when your deployment offers
    one, falling back to a local server otherwise. Pass `--local-server` to force
    the local server.
    """
    deployment_type = (
        None if deployment is None else _DEPLOYMENT_FLAG_VALUES[deployment]
    )
    if deployment_type is interactive_helpers.DeploymentType.SELF_HOSTED:
        workspace = workspace or os.environ.get("OPIK_WORKSPACE", "").strip() or None
    _check_connection_flags(deployment_type, url=url, workspace=workspace)
    run_configure(
        local_server=local_server,
        hosts=hosts,
        skills_flag=skills_flag,
        ignore_opik_config=ignore_opik_config,
        invoked_via="direct",
        deployment=deployment_type,
        url=url,
        workspace=workspace,
    )


@analytics.entry_point
def run_configure(
    local_server: bool,
    hosts: Tuple[str, ...],
    skills_flag: Optional[bool],
    ignore_opik_config: bool,
    invoked_via: str,
    deployment: Optional[interactive_helpers.DeploymentType] = None,
    url: Optional[str] = None,
    workspace: Optional[str] = None,
) -> None:
    """The `opik mcp configure` flow, also entered from `opik configure`.

    `@entry_point` keeps its events reported on that redirect, where analytics
    would otherwise drop them as a nested SDK call. ``deployment``, ``url`` and
    ``workspace`` are the flags, already checked against each other.
    """
    # Before the first event, so every event of this run carries how it was
    # entered: a redirect from `opik configure` converts differently from a cold
    # run, and the funnel has to tell them apart.
    environment_details.set_run_context(invoked_via=invoked_via)

    # `--deployment` answers the question the saved config would, so it replaces it.
    saved = (
        None
        if ignore_opik_config or deployment is not None
        else opik_config.OpikConfig()
    )
    # With either flag the run starts where a machine with no config does: Cloud,
    # no key — which is also what its entry event reports.
    params = _opik_cloud_params() if saved is None else _resolve_setup_params(saved)

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
        ignore_opik_config=ignore_opik_config,
        # Empty when the saved config or the question decides.
        deployment_flag=_deployment_name(deployment),
        # This command reuses an existing Opik configuration, so the account is
        # normally known from the start — this is the MCP funnel's entry point.
        **_identity(params),
    )

    # Where the run is, for the failure event below. Ctrl-C at a question or a
    # crash would otherwise end the run with no event at all, and a drop between
    # the entry event and the result would have no place.
    stage = "start"
    try:
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

        detected = mcp_targets.detected_targets()
        detected_clients = len(detected)

        # A scripted run (`--ai-client`) gets no banner, and a redirect already
        # showed the logo.
        if not host_keys and invoked_via == "direct":
            install_view.render_mcp_banner()

        if deployment is not None:
            stage = "deployment"
            params = _flag_params(deployment, url=url, workspace=workspace)
            install_view.render_connection(
                opik_url=params["base_url"],
                # Cloud's is the one the user signs in to; a local Opik has one.
                workspace=params["workspace"] if params["self_hosted_comet"] else None,
                source="the --deployment flag",
                change_command=None,
            )
        elif saved is not None and not _needs_opik_configuration(params):
            # A redirect from `opik configure` has just shown these settings.
            if invoked_via == "direct":
                install_view.render_connection(
                    opik_url=params["base_url"],
                    # A local deployment has the one workspace, `default`.
                    workspace=None if params["use_local"] else params["workspace"],
                    source=_saved_source(saved),
                )
        elif not interactive_helpers.is_interactive():
            if saved is None:
                # `--ignore-opik-config`, which asks, with no terminal to ask in.
                raise click.ClickException(
                    "`--ignore-opik-config` asks which Opik to connect to, which needs "
                    "an interactive terminal. Without one, name it with --deployment: "
                    "cloud, local or self-hosted."
                )
            stage = "deployment"
            if _names_an_opik(saved):
                raise click.ClickException(
                    f"{_saved_source(saved)} names an Opik but no API key, and there "
                    "is no terminal to ask for one. Set OPIK_API_KEY in the "
                    "environment and re-run, or name the Opik to connect to:\n\n"
                    + _deployment_commands(hosts)
                )
            # Nothing names an Opik and nobody to ask: a local one that answers is
            # the one this machine runs.
            found = _local_params(
                opik_configure.OPIK_BASE_URL_LOCAL,
                check_tls_certificate=saved.check_tls_certificate,
            )
            if not opik_rest_helpers.is_instance_active(found["base_url"]):
                raise click.ClickException(
                    "Opik is not configured on this machine (no ~/.opik.config, no "
                    "OPIK_API_KEY), no Opik answers at "
                    f"{opik_configure.OPIK_BASE_URL_LOCAL}, and there is no terminal "
                    "to ask which one to connect to. Name it:\n\n"
                    + _deployment_commands(hosts)
                )
            params = found
            install_view.render_connection(
                opik_url=params["base_url"],
                workspace=None,
                source="found running; nothing is saved in ~/.opik.config",
                change_command=(
                    "opik mcp configure "
                    + " ".join(f"--ai-client {host}" for host in hosts)
                    + " --deployment cloud"
                ),
            )
        else:
            # No usable config (none, one without an API key, or one the flag set
            # aside). The deployment picker comes first; the client picker follows,
            # inside `setup` below, which is the order the onboarding funnel counts
            # them in. Nothing is written to ~/.opik.config either way: the answers
            # go to the AI client's config. Cloud needs no API key at all (the
            # hosted server signs in with OAuth).
            stage = "deployment"
            answer = configure_cli.ask_for_deployment_type(MCP_DEPLOYMENT_QUESTION)
            if answer is interactive_helpers.DeploymentType.CLOUD:
                params = _opik_cloud_params()
            else:
                stage = "credentials"
                params = cast(McpSetupParams, configure_cli.ask_for_connection(answer))
                # A gap before the client picker, which the questions do not leave.
                click.echo()
            if ignore_opik_config:
                # A saved "don't check certificates" was made for the saved Opik; this
                # run connects to another one, and sends it the API key to verify.
                params["check_tls_certificate"] = True

        # Installed unless refused: the pack is what teaches the client to use the
        # server just registered.
        skills_verdict = consent.resolve_installed_by_default(skills_flag)

        stage = "assistants"

        outcome = assistants.setup(
            params,
            install_mcp=True,
            skills=skills_verdict,
            force_local_server=local_server,
            host_keys=host_keys,
        )

        # Before the result event: the launch branch replaces this process.
        stage = "handoff"
        handoff = _resolve_handoff(params, outcome)
    except BaseException as exception:
        # `opik configure`'s failure event, in the same shape so one query reads
        # both. From this frame, beside the entry event: one raised further down
        # would be dropped as nested. Only the exception's type is reported: its
        # message can carry a URL, a workspace or a key.
        analytics.track_event(
            "configuration",
            "mcp_configure",
            "failed",
            stage=stage,
            error_type=type(exception).__name__,
            interactive=interactive_helpers.is_interactive(),
            ignore_opik_config=ignore_opik_config,
            deployment_flag=_deployment_name(deployment),
            **_identity(params),
        )
        raise

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
        # `diagnose`, `instrument`, or `check_first` when there was no key to look
        # with; empty when nothing was offered.
        closing_prompt=handoff.prompt_kind or "",
        ignore_opik_config=ignore_opik_config,
        deployment_flag=_deployment_name(deployment),
        # Resolved again, not reused: the answers on the way through are what turn
        # an unconfigured run into an attributed one.
        **_identity(params),
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
    #: Which first prompt: `diagnose`, `instrument` or `check_first`.
    prompt_kind: Optional[str] = None
    display_name: Optional[str] = None
    prompt: Optional[str] = None
    #: How to start the client with the prompt; None for one that cannot be.
    command: Optional[List[str]] = None
    #: Declined with Ctrl-C rather than `n`, so the ending replaces the prompt line.
    quit_at_offer: bool = False
    #: What is left to do in each client, for a run without a terminal.
    next_steps: Tuple[str, ...] = ()


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
        return _Handoff(
            outcome="no_terminal",
            wrote_config=wrote_config,
            next_steps=tuple(
                mcp_view.next_steps(
                    outcome.registered_clients,
                    hosted=outcome.transport == mcp_spec.McpConnectionMode.REMOTE.value,
                    sign_in_pending=outcome.sign_in_pending,
                    signed_in=outcome.signed_in,
                )
            ),
        )
    if len(outcome.registered_clients) != 1:
        return _Handoff(outcome="not_single_client", wrote_config=wrote_config)
    if outcome.sign_in == "failed":
        # Without a sign-in the server has no tools, so the agent could not
        # answer; the installer has already said how to finish signing in.
        return _Handoff(outcome="sign_in_failed", wrote_config=True)

    host_key = outcome.registered_clients[0]
    target = mcp_targets.find_target(host_key)
    display_name = target.display_name if target is not None else host_key

    if params["api_key"] or params["use_local"]:
        try:
            project = mcp_handoff.traced_project(
                api_key=params["api_key"],
                workspace=params["workspace"],
                api_url=params["api_url"],
            )
        except KeyboardInterrupt:
            # This runs silently after the rows, where Ctrl-C is likely; the run
            # is still reported, since the config was written.
            return _Handoff(outcome="interrupted", wrote_config=True)
        prompt_kind = "diagnose" if project is not None else "instrument"
        prompt = mcp_handoff.closing_prompt(project)
    else:
        # No key to look with (Cloud signed in over OAuth): the agent can.
        prompt_kind = "check_first"
        prompt = mcp_handoff.CHECK_FIRST_PROMPT

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
    quit_at_offer = False
    try:
        accepted = install_view.confirm_default_yes(f"Run in {display_name}")
    except click.Abort:
        # Ctrl-C here means "not now", the same as `n`.
        accepted, quit_at_offer = False, True

    return _Handoff(
        outcome="launch" if accepted else "declined",
        quit_at_offer=quit_at_offer,
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
        if handoff.outcome == "no_terminal" and handoff.wrote_config:
            install_view.render_next_steps(handoff.next_steps)
        elif handoff.wrote_config and handoff.outcome != "sign_in_failed":
            install_view.render_restart_note(mcp_installed=True)
        return

    if handoff.command is None:
        install_view.render_prompt_to_paste(handoff.display_name, handoff.prompt)
        return

    if handoff.outcome == "declined":
        install_view.render_handoff_declined(
            handoff.display_name, replace_offer=handoff.quit_at_offer
        )
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
