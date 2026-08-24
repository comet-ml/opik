"""The shared "set Opik up for your AI assistants" step.

Both ``opik mcp configure`` and the tail of ``opik configure`` do the same thing —
pick what to install, pick which assistants, register the server, install the skill
pack — so it lives here once rather than being assembled twice.

Kept in the CLI layer because it renders: ``configurator`` is reachable from
``opik.configure()``, which is a library call and keeps its plain-text prompts.
"""

import logging
from typing import Any, List, Mapping, Optional, Sequence

import click

from opik.cli import mcp_view as mcp_rich_view
from opik.configurator.mcp import view as mcp_view
from opik.cli import selector
from opik.configurator import mcp as mcp_installer
from opik.configurator import skills as skills_installer
from opik.configurator.skills import roots as skills_roots

LOGGER = logging.getLogger(__name__)

COMPONENT_MCP = "mcp"
COMPONENT_SKILLS = "skills"


def choose_components(skills_flag: Optional[bool]) -> List[str]:
    """What to set up: the server, the skill pack, or both.

    Both by default — they answer two halves of the same problem, and someone who
    wants the tools almost always wants the guidance for using them. A selector
    rather than one merged yes/no so it stays visible that two different things
    get written: the server puts credentials in a config file, the pack puts
    instructions the assistant then acts on.
    """
    both = [COMPONENT_MCP, COMPONENT_SKILLS]

    if skills_flag is False:
        return [COMPONENT_MCP]
    if skills_flag is True:
        return both
    if not selector.is_supported():
        return both

    chosen = selector.multiselect(
        title="What should Opik set up?",
        choices=[
            selector.Choice(
                COMPONENT_MCP,
                "MCP server",
                "read traces, log scores and run experiments from chat",
            ),
            selector.Choice(
                COMPONENT_SKILLS,
                "Skill pack",
                "teaches your assistant how to instrument your code",
            ),
        ],
        preselected=both,
    )
    if chosen is None:
        raise click.ClickException("Cancelled; nothing was changed.")
    return chosen


def setup(
    setup_params: Mapping[str, Any],
    force_local_server: bool = False,
    host_keys: Optional[List[str]] = None,
    skills_flag: Optional[bool] = None,
) -> None:
    """Run the assistant setup: components, then hosts, then install.

    ``setup_params`` is the connection block ``configurator.mcp`` needs — api key,
    workspace, base and api urls, deployment flags.
    """
    components = choose_components(skills_flag)
    if not components:
        raise click.ClickException("Nothing selected; nothing was changed.")

    wants_skills = COMPONENT_SKILLS in components
    configured_hosts: List[str] = []

    if COMPONENT_MCP in components:
        configured_hosts = mcp_installer.setup_mcp_server(
            **dict(setup_params),
            force_local_server=force_local_server,
            host_keys=host_keys,
            view=mcp_rich_view.RichInstallView(),
            plan_extras=(
                [mcp_view.PlannedTarget("Skill pack", "for the same assistants")]
                if wants_skills
                else []
            ),
            # The closing "restart your assistant" line is printed once, at the
            # end of the whole step, rather than by each half.
            announce_next_steps=not wants_skills,
        )

    if not wants_skills:
        return

    # Reuse the assistants the server was just set up for, so the same question
    # is not asked twice. With no server step there is nothing to reuse.
    skill_hosts: Optional[Sequence[str]] = configured_hosts or host_keys
    if not skill_hosts:
        skill_hosts = _ask_which_hosts()
        if skill_hosts is None:
            raise click.ClickException("Cancelled; the skill pack was not installed.")

    if skill_hosts:
        skills_installer.setup_skills(list(skill_hosts), announce_next_steps=False)
        mcp_rich_view.RichInstallView().next_steps(
            skills_roots.display_names(list(skill_hosts))
        )


def _ask_which_hosts() -> Optional[List[str]]:
    """Which detected assistants to install the skill pack for."""
    detected = skills_roots.detected_host_keys()
    if len(detected) == 0:
        raise click.ClickException(
            "No supported AI host was detected. Name one explicitly with `--host`."
        )

    labels = skills_roots.display_names(detected)
    if len(detected) == 1 or not selector.is_supported():
        if click.confirm(
            f"Install the Opik skill pack for {', '.join(labels)}?", default=True
        ):
            return detected
        return []

    return selector.multiselect(
        title="Which AI assistants should the Opik skill pack be installed for?",
        choices=[
            selector.Choice(key=key, label=label)
            for key, label in zip(detected, labels)
        ],
        preselected=detected,
    )
