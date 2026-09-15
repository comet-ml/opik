"""The shared "set Opik up for your AI client" step.

Both ``opik mcp configure`` and the tail of ``opik configure`` do the same thing —
register the server, then offer the skill pack — so it lives here once rather than
being assembled twice.

The two halves are independent: either can run without the other. They were welded
together once, because the skill pack installs into the clients the server was just
registered with, and reusing that list was easier than deriving it. The cost was
that "skills but not the server" could not be expressed, so ``--no-install-mcp``
silently registered the server anyway. The list now falls back to what is detected,
which is what makes both halves optional.

This module does not decide anything: ``configurator.consent`` does that, and the
caller passes the answers in. Kept in the CLI layer because it renders —
``configurator`` is reachable from ``opik.configure()``, which is a library call
and keeps its plain-text prompts.
"""

import click
from typing import Any, List, Mapping, NamedTuple, Optional

from opik.cli import install_view
from opik.configurator import consent
from opik.configurator import mcp as mcp_installer
from opik.configurator import skills as skills_installer
from opik.configurator.skills import roots as skills_roots


class Outcome(NamedTuple):
    """What the step actually did, for the caller to report.

    Returned rather than reported from here: analytics drops an event whose
    immediate caller is a different ``opik`` module, so only the click command at
    the top of the stack can report. This carries the result up to it.
    """

    clients: int
    skills: bool


NOTHING_DONE = Outcome(clients=0, skills=False)


def setup(
    setup_params: Mapping[str, Any],
    *,
    install_mcp: bool,
    skills: consent.Verdict,
    host_keys: Optional[List[str]] = None,
    force_local_server: bool = False,
    assume_confirmed: bool = False,
) -> Outcome:
    """Register the MCP server and/or install the skill pack.

    ``setup_params`` is the connection block ``configurator.mcp`` needs — api key,
    workspace, base and api urls, deployment flags.

    The skill-pack question is asked here rather than by the caller, because it
    must land after the server's results table — see :func:`_ask_about_skill_pack`.

    ``install_mcp`` is already resolved: the question names the clients it would
    write to, so the caller asks it before this runs. ``skills`` arrives as a
    verdict instead, because that question is deliberately asked *after* the
    server's results table, so the user answers it with the outcome in front of
    them.
    """
    # One view for the whole step, not one per half: it carries what the server
    # install learned — notably whether the connection needs a sign-in — through
    # to the closing block below, which is printed after the skill pack.
    view = install_view.RichInstallView()

    configured_hosts = (
        mcp_installer.setup_mcp_server(
            **dict(setup_params),
            force_local_server=force_local_server,
            host_keys=host_keys,
            assume_confirmed=assume_confirmed,
            view=view,
            # The closing "restart your assistant" line is printed once, at the end
            # of the whole step, rather than by each half.
            announce_next_steps=False,
        )
        if install_mcp
        else []
    )

    # Where the pack goes: the clients we just registered, or — when the server
    # step was declined or skipped — whatever is on this machine. An empty list is
    # passed through rather than special-cased, because `setup_skills` already
    # names the clients it could not place the pack in, and it is the part that
    # knows which locations are supported.
    skills_targets = configured_hosts or skills_installer.detected_host_keys()

    installed_skills = False

    if consent.granted(skills, _ask_about_skill_pack):
        with view.step("Fetching the Opik skill pack"):
            result = skills_installer.setup_skills(skills_targets)
        installed_skills = install_view.render_skill_pack(result, view)

    components = [
        name
        for name, done in (
            ("MCP server", bool(configured_hosts)),
            ("skill pack", installed_skills),
        )
        if done
    ]
    if not components:
        return NOTHING_DONE

    view.done(
        components, skills_roots.display_names(configured_hosts or skills_targets)
    )

    return Outcome(clients=len(configured_hosts), skills=installed_skills)


def _ask_about_skill_pack() -> bool:
    """Offer the skill pack, once the server step's results are on screen.

    The clients are not named again: the results table directly above this already
    lists them, and repeating three of them buries the question.
    """
    install_view.console.print()
    return click.confirm(
        click.style("Recommended", fg="green")
        + ": also install the Opik skill pack?\n"
        + click.style(consent.SKILL_PACK_PITCH, dim=True),
        default=True,
    )
