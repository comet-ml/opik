"""The shared "set Opik up for your AI assistants" step.

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
from opik.configurator import mcp as mcp_installer
from opik.configurator import skills as skills_installer
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

    if _wants_skill_pack(skills_flag):
        skills_installer.setup_skills(configured_hosts, announce_next_steps=False)

    mcp_rich_view.RichInstallView().next_steps(
        skills_roots.display_names(configured_hosts)
    )


def _wants_skill_pack(skills_flag: Optional[bool]) -> bool:
    """Whether to add the skill pack, asking only when the flags left it open.

    Recommended, and so defaulted to yes: the server gives an assistant the tools
    and the pack gives it the knowledge of how to use them, and the telemetry says
    the second half is where installs stall.
    """
    if skills_flag is not None:
        return skills_flag

    # The assistants are not named again: the results table directly above this
    # already lists them, and repeating three of them buries the question.
    mcp_rich_view.console.print()
    return click.confirm(
        click.style("Recommended", fg="green")
        + ": also install the Opik skill pack?\n"
        + click.style(SKILL_PACK_PITCH, dim=True),
        default=True,
    )
