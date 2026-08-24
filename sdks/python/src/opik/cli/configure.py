"""Configure command for Opik CLI."""

import logging
from typing import Any, Mapping, Optional

import click

import opik.config as opik_config
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
) -> None:
    """The CLI's assistant step: selectors and formatted output.

    `-y` deliberately does not reach into another tool's configuration, and an
    explicit `--no-install-mcp` with `--no-install-skills` means neither.
    """
    if install_mcp is False and install_skills is False:
        return
    if install_mcp is None and install_skills is None and automatic_approvals:
        return

    skills_flag = install_skills
    if install_mcp is False:
        # Server declined outright: only the pack is on the table.
        if skills_flag is False:
            return
        assistants.setup(setup_params, skills_flag=True, host_keys=None)
        return

    if install_mcp is None and not _confirm_assistant_step():
        return

    assistants.setup(setup_params, skills_flag=skills_flag)


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
        # No terminal to ask in. `--install-mcp` is how a script opts in, and it
        # skips this path entirely by setting `install_mcp` to True.
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


def run_interactive_configure(
    use_local: bool = False,
    automatic_approvals: bool = False,
    install_mcp: Optional[bool] = None,
    install_skills: Optional[bool] = None,
) -> None:
    """Programmatic entry to the interactive ``opik configure`` flow.

    Reused by ``opik connect`` / ``opik endpoint`` so they can auto-launch
    configuration when no ~/.opik.config is present.
    """
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
            assistant_setup=_setup_assistants,
        ).configure()
        return

    deployment_type_choice = interactive_helpers.ask_user_for_deployment_type()

    if deployment_type_choice == interactive_helpers.DeploymentType.CLOUD:
        configurator = opik_configure.OpikConfigurator(
            url=opik_configure.OPIK_BASE_URL_CLOUD,
            use_local=False,
            force=True,
            self_hosted_comet=False,
            automatic_approvals=automatic_approvals,
            install_mcp=install_mcp,
            install_skills=install_skills,
            assistant_setup=_setup_assistants,
        )
    elif deployment_type_choice == interactive_helpers.DeploymentType.SELF_HOSTED:
        configurator = opik_configure.OpikConfigurator(
            use_local=False,
            force=True,
            self_hosted_comet=True,
            automatic_approvals=automatic_approvals,
            install_mcp=install_mcp,
            install_skills=install_skills,
            assistant_setup=_setup_assistants,
        )
    elif deployment_type_choice == interactive_helpers.DeploymentType.LOCAL:
        configurator = opik_configure.OpikConfigurator(
            use_local=True,
            force=True,
            self_hosted_comet=False,
            automatic_approvals=automatic_approvals,
            install_mcp=install_mcp,
            install_skills=install_skills,
            assistant_setup=_setup_assistants,
        )
    else:
        raise click.ClickException("Unknown deployment type was selected. Exiting.")

    configurator.configure()


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
    """
    Create a configuration file for the Opik Python SDK, if a configuration file already exists, it will be overwritten.
    This is also available as a function in the Python SDK.
    """
    # Running `opik configure` with no subcommand performs the configuration
    # itself; `opik configure status` (and any future subcommand) is dispatched
    # by Click instead.
    if ctx.invoked_subcommand is not None:
        return

    run_interactive_configure(
        use_local=use_local,
        automatic_approvals=yes,
        install_mcp=install_mcp,
        install_skills=install_skills,
    )


@configure.command(name="status")
def status() -> None:
    """Show the active Opik configuration: file path, environment, and workspace."""
    status_view.render_config_summary(opik_config.OpikConfig())
