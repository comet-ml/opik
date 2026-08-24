"""The shared "set Opik up for your AI assistant" step.

Both ``opik mcp configure`` and the tail of ``opik configure`` do the same thing —
register the server, then offer the skill pack — so it lives here once rather than
being assembled twice.

The server goes in first and the pack is offered after it, rather than asking the
user to choose between them up front: by the time the question arrives they can
see what just happened, and the pack only makes sense for hosts the server was
actually registered with.

Kept in the CLI layer because it renders: ``configurator`` is reachable from
``opik.configure()``, which is a library call and keeps its plain-text prompts.
"""

import logging
from typing import Any, List, Mapping, Optional

import click

from opik.cli import mcp_view as mcp_rich_view
from opik.configurator import interactive_helpers
from opik.configurator import mcp as mcp_installer
from opik.configurator import skills as skills_installer
from opik.configurator.skills import install as skills_install
from opik.configurator.mcp import view as mcp_view
from opik.configurator.skills import roots as skills_roots

LOGGER = logging.getLogger(__name__)

SKILL_PACK_PITCH = (
    "It teaches your assistant how to instrument code with Opik, wire up "
    "integrations, and run test suites."
)


def setup(
    setup_params: Mapping[str, Any],
    force_local_server: bool = False,
    host_keys: Optional[List[str]] = None,
    skills_flag: Optional[bool] = None,
) -> None:
    """Register the MCP server, then offer the skill pack for the same assistants.

    ``setup_params`` is the connection block ``configurator.mcp`` needs — api key,
    workspace, base and api urls, deployment flags.
    """
    configured_hosts = mcp_installer.setup_mcp_server(
        **dict(setup_params),
        force_local_server=force_local_server,
        host_keys=host_keys,
        view=mcp_rich_view.RichInstallView(),
        # The closing "restart your assistant" line is printed once, at the end of
        # the whole step, rather than by each half.
        announce_next_steps=False,
    )

    if not configured_hosts:
        # Nothing was registered, so there is no assistant to add a pack to and
        # the installer has already explained why.
        return

    view = mcp_rich_view.RichInstallView()
    components = ["MCP server"]

    if _wants_skill_pack(skills_flag, view):
        with view.step("Fetching the Opik skill pack"):
            result = skills_installer.setup_skills(configured_hosts)
        if render_skill_pack(result, view):
            components.append("skill pack")

    view.done(components, skills_roots.display_names(configured_hosts))


def render_skill_pack(
    result: skills_install.InstallResult, view: mcp_view.InstallView
) -> bool:
    """Report a skill-pack install. Returns whether it succeeded."""
    if not result.succeeded:
        view.problem(f"Could not install the Opik skill pack: {result.error}.")
        return False

    view.results(
        [
            mcp_view.TargetResult(
                display_name="Skill pack",
                detail=f"{', '.join(result.skills)} in {result.shared_dir}",
                succeeded=True,
                summary=", ".join(result.skills),
            )
        ]
    )
    for host_key, message in result.link_errors.items():
        label = ", ".join(skills_roots.display_names([host_key]))
        view.problem(f"{label}: {message}")
    if result.plugin_overlap:
        view.note(
            "The Opik Claude Code plugin also ships an `opik` skill, so Claude "
            "Code now has both. Remove the plugin's copy with "
            "`/plugin uninstall opik` if you prefer the pack alone."
        )
    return True


def _wants_skill_pack(skills_flag: Optional[bool], view: mcp_view.InstallView) -> bool:
    """Whether to add the skill pack, asking only when the flags left it open.

    Recommended, and so defaulted to yes: the server gives an assistant the tools
    and the pack gives it the knowledge of how to use them, and the telemetry says
    the second half is where installs stall.

    Without a terminal there is nobody to ask, and asking anyway aborted the run
    *after* the server had already been registered. An explicit ``--skills`` is
    the asking in that case, exactly as ``--install-mcp`` is for the server.
    """
    if skills_flag is not None:
        return skills_flag

    if not interactive_helpers.is_interactive():
        view.note(
            "Skipping the Opik skill pack: no interactive terminal. Pass "
            "`--skills` to install it without being asked."
        )
        return False

    # The assistants are not named again: the results table directly above this
    # already lists them, and repeating three of them buries the question.
    mcp_rich_view.console.print()
    return click.confirm(
        click.style("Recommended", fg="green")
        + ": also install the Opik skill pack?\n"
        + click.style(SKILL_PACK_PITCH, dim=True),
        default=True,
    )
