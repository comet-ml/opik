"""Configure command for Opik CLI."""

import logging
import os
import urllib.parse
from typing import Any, Mapping, Optional

import click

import opik.config as opik_config
from opik import analytics
from opik.cli import assistants
from opik.cli import install_view
from opik.cli import status_view
from opik.configurator import assistants as assistant_policy
from opik.configurator import configure as opik_configure, interactive_helpers
from opik.configurator import mcp as mcp_installer

LOGGER = logging.getLogger(__name__)


def _setup_assistants(
    setup_params: Mapping[str, Any],
    install_mcp: Optional[bool],
    install_skills: Optional[bool],
    automatic_approvals: bool,
) -> assistants.Outcome:
    """The CLI's assistant step: selectors and formatted output.

    `-y` deliberately does not reach into another tool's configuration, and an
    explicit `--no-install-mcp` with `--no-install-skills` means neither.
    """
    if install_mcp is False and install_skills is False:
        return assistants.NOTHING_DONE
    if install_mcp is None and install_skills is None and automatic_approvals:
        # `-y` alone is not a request to edit another tool's config, so this is a
        # skip — and it is the one an agent is most likely to hit, because `-y` is
        # what the previous error told it to add.
        _announce_assistant_skip()
        return assistants.NOTHING_DONE

    skills_flag = install_skills
    if install_mcp is False:
        # Server declined outright: only the pack is on the table.
        if skills_flag is False:
            return assistants.NOTHING_DONE
        return assistants.setup(setup_params, skills_flag=True, host_keys=None)

    # An explicit `--install-mcp` is the request; only an unflagged run needs to ask.
    if install_mcp is None and not _confirm_assistant_step():
        return assistants.NOTHING_DONE

    return assistants.setup(
        setup_params,
        skills_flag=skills_flag,
        assume_confirmed=install_mcp is True,
    )


def _announce_assistant_skip() -> None:
    """Say that the assistant step was skipped, and how to include it.

    Staying silent reported "configuration completed successfully" to a caller
    that had also asked for the MCP server, with no way to notice the difference
    between "configured Opik" and "configured Opik and your client".

    Shown with a terminal too. `-y` reads as yes-to-everything, so someone who
    typed it chose "stop asking me questions", not "skip my editor" — the same
    surprise an agent hits, and worth the one line either way.
    """
    console = install_view.console
    console.print(
        "  Skipped AI client setup: nothing named it, so nothing was written to "
        "your AI client's config.",
        style="yellow",
    )
    console.print(
        "  To include it:  opik configure --install-mcp --install-skills",
        style="dim",
    )


def _confirm_assistant_step() -> bool:
    """Ask before touching any assistant's configuration.

    `opik configure` is about writing ``~/.opik.config``; registering an MCP
    server edits files owned by other tools, which is a different kind of
    permission and should not be assumed just because the user configured Opik.
    Dropping straight into the host picker made the wider question unaskable —
    there was no way to answer "no, just configure Opik".

    Not asked on `opik mcp configure`: running that command *is* the answer.
    Defaults to no, matching `opik configure -y`'s refusal to reach into
    another tool's config.
    """
    detected = mcp_installer.detected_host_names()
    if len(detected) == 0:
        # Nothing found to register, so there is nothing worth asking about.
        return False

    if not interactive_helpers.is_interactive():
        _announce_assistant_skip()
        return False

    console = install_view.console
    console.print()
    console.print("  Set Opik up for your AI client?", style="bold")
    # One sentence per print: the host list varies in length, and folding it into
    # a line with a hardcoded wrap pushed the rest past the terminal width and
    # lost the indent on the continuation.
    console.print(f"  Found {assistant_policy.readable_list(detected)}.", style="dim")
    console.print(
        "  The Opik MCP server lets it read traces, log scores and run\n"
        "  experiments from chat.",
        style="dim",
    )
    return click.confirm("", default=False)


def _is_comet_cloud_host(url: str) -> bool:
    """Whether ``url`` points at Comet-hosted Opik.

    Matches the parsed hostname, not a substring of the URL: `endswith("comet.com")`
    also accepts `evil-comet.com`, and `"comet.com" in url` accepts anything with
    it in a path or query. A suffix match needs the dot to be a real label
    boundary, which is what makes `notcomet.com` fail and `www.comet.com` pass.
    """
    host = (urllib.parse.urlparse(url).hostname or "").lower()
    return host == "comet.com" or host.endswith(".comet.com")


def _deployment_type() -> interactive_helpers.DeploymentType:
    """Which Opik deployment to configure — asked, or inferred without a terminal.

    The picker was the first thing `opik configure` did, and it used `input()`, so
    the whole command aborted for any caller without a tty — a coding agent asked
    to "set up Opik" included, which is the case this exists for. `-y` did not help,
    because there is no sensible default deployment to say yes to.

    Unattended, the environment already answers the question: `OPIK_URL_OVERRIDE`
    says where Opik is, and its shape says what kind. Only when nothing is set is
    there really nothing to go on, and then the error names what to provide rather
    than reporting an abort.
    """
    if interactive_helpers.is_interactive():
        return interactive_helpers.ask_user_for_deployment_type()

    url = os.environ.get("OPIK_URL_OVERRIDE", "").strip()
    if url:
        if _is_comet_cloud_host(url):
            return interactive_helpers.DeploymentType.CLOUD
        if "/opik/api" in url:
            # The Comet platform's path shape, on someone else's host.
            return interactive_helpers.DeploymentType.SELF_HOSTED
        return interactive_helpers.DeploymentType.LOCAL

    if os.environ.get("OPIK_API_KEY", "").strip():
        # A key with no URL only makes sense for Opik Cloud.
        return interactive_helpers.DeploymentType.CLOUD

    raise click.ClickException(
        "`opik configure` cannot tell which Opik deployment to use, and there is "
        "no terminal to ask in. Set one of these and re-run:\n\n"
        "    OPIK_API_KEY=<key>                     # Opik Cloud\n"
        "    OPIK_URL_OVERRIDE=<url> OPIK_API_KEY=<key>   # self-hosted\n"
        "    opik configure --use_local -y          # local Opik\n"
    )


def run_interactive_configure(
    use_local: bool = False,
    automatic_approvals: bool = False,
    install_mcp: Optional[bool] = None,
    install_skills: Optional[bool] = None,
) -> assistants.Outcome:
    """Programmatic entry to the interactive ``opik configure`` flow.

    Reused by ``opik connect`` / ``opik endpoint`` so they can auto-launch
    configuration when no ~/.opik.config is present.

    Returns what the assistant step did, so the command that owns the analytics
    event can report it. The configurator takes the step as a callback and
    discards its return value, hence the recorder rather than a plain return.
    """
    recorded = assistants.NOTHING_DONE

    def record(*args: Any) -> None:
        nonlocal recorded
        recorded = _setup_assistants(*args)

    if use_local:
        # The configurator class rather than the `configure()` helper: the skills
        # flag and the renderer are CLI-internal wiring, not part of the public
        # library signature.
        opik_configure.OpikConfigurator(
            use_local=True,
            force=True,
            automatic_approvals=automatic_approvals,
            install_mcp=install_mcp,
            install_skills=install_skills,
            assistant_setup=record,
        ).configure()
        return recorded

    deployment_type_choice = _deployment_type()

    if deployment_type_choice == interactive_helpers.DeploymentType.CLOUD:
        configurator = opik_configure.OpikConfigurator(
            url=opik_configure.OPIK_BASE_URL_CLOUD,
            use_local=False,
            force=True,
            self_hosted_comet=False,
            automatic_approvals=automatic_approvals,
            install_mcp=install_mcp,
            install_skills=install_skills,
            assistant_setup=record,
        )
    elif deployment_type_choice == interactive_helpers.DeploymentType.SELF_HOSTED:
        configurator = opik_configure.OpikConfigurator(
            use_local=False,
            force=True,
            self_hosted_comet=True,
            automatic_approvals=automatic_approvals,
            install_mcp=install_mcp,
            install_skills=install_skills,
            assistant_setup=record,
        )
    elif deployment_type_choice == interactive_helpers.DeploymentType.LOCAL:
        configurator = opik_configure.OpikConfigurator(
            use_local=True,
            force=True,
            self_hosted_comet=False,
            automatic_approvals=automatic_approvals,
            install_mcp=install_mcp,
            install_skills=install_skills,
            assistant_setup=record,
        )
    else:
        raise click.ClickException("Unknown deployment type was selected. Exiting.")

    configurator.configure()

    return recorded


@click.group(
    name="configure",
    invoke_without_command=True,
    context_settings={"ignore_unknown_options": True},
)
@click.option(
    "--use_local",
    "--use-local",
    is_flag=True,
    default=False,
    help="Flag to configure the Opik Python SDK for local Opik deployments.",
)
@click.option(
    "-y",
    "--yes",
    is_flag=True,
    default=False,
    help="Flag to automatically answer `yes` whenever a user approval might be required",
)
@click.option(
    "--install-mcp/--no-install-mcp",
    default=None,
    help="Register the Opik MCP server with detected AI clients (Claude Code, Cursor, "
    "VS Code, Codex, opencode). When omitted, you are prompted interactively.",
)
@click.option(
    "--install-skills/--no-install-skills",
    default=None,
    help="Install the Opik skill pack into detected AI clients, teaching your "
    "AI client how to instrument code with Opik. When omitted, you are prompted "
    "interactively.",
)
@click.pass_context
def configure(
    ctx: click.Context,
    use_local: bool,
    yes: bool,
    install_mcp: Optional[bool],
    install_skills: Optional[bool],
) -> None:
    """Create a configuration file for the Opik Python SDK.

    Overwrites an existing configuration file. Also available as a function in the
    Python SDK.

    Without a terminal — a coding agent, a script — the defaults are assumed, so
    one command does everything:

        opik configure --install-mcp --install-skills

    On its own it configures Opik and nothing else: registering the MCP server
    edits your AI client's own config, so it happens only when asked for.
    Deployment is taken from OPIK_URL_OVERRIDE / OPIK_API_KEY, or use --use_local.
    """
    # Running `opik configure` with no subcommand performs the configuration
    # itself; `opik configure status` (and any future subcommand) is dispatched
    # by Click instead.
    if ctx.invoked_subcommand is not None:
        return

    # Reported from the click command, not from the configurator underneath it:
    # analytics treats a configurator call made from `opik.cli` as Opik calling
    # itself and drops it, which is right for `Opik.get_dataset` and wrong here.
    # Click is the caller at this frame, so the event survives — and because the
    # outermost reporter suppresses nested ones, this is also the only place the
    # flow can report from.
    analytics.track_event(
        "configuration",
        "configure",
        interactive=interactive_helpers.is_interactive(),
        # The tri-states as passed, so "asked and said yes" is separable from
        # "never asked" — the flag is also how an agent drives this.
        install_mcp=str(install_mcp),
        install_skills=str(install_skills),
    )

    # With no terminal there is nobody to ask, and every question here has a sane
    # default: use the local instance we found, keep the project name we derived.
    # Demanding `-y` to say "yes, the defaults" was a step that existed only to be
    # discovered — and the error teaching it was the step an agent was most likely
    # to stop at.
    outcome = run_interactive_configure(
        use_local=use_local,
        automatic_approvals=yes or not interactive_helpers.is_interactive(),
        install_mcp=install_mcp,
        install_skills=install_skills,
    )

    # Sibling of the entry event above, reported from this same frame. Entry says
    # what was asked for, this says what was actually written — the gap between
    # the two is the drop-off worth watching.
    analytics.track_event(
        "configuration",
        "configure",
        "result",
        clients_written=outcome.clients,
        skills_installed=outcome.skills,
    )


@configure.command(name="status")
def status() -> None:
    """Show the active Opik configuration: file path, environment, and workspace."""
    status_view.render_config_summary(opik_config.OpikConfig())
