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

from typing import Any, List, Mapping, NamedTuple, Optional, Tuple

from opik.cli import install_view
from opik.configurator import consent
from opik.configurator import mcp as mcp_installer
from opik.configurator.mcp import install as mcp_install
from opik.configurator import skills as skills_installer
from opik.configurator.skills import roots as skills_roots


class Outcome(NamedTuple):
    """What the step actually did, for the caller to report.

    Returned rather than reported from here: analytics drops an event whose
    immediate caller is a different ``opik`` module, so only the click command at
    the top of the stack can report. This carries the result up to it.

    ``clients`` alone could not say why it was zero, which is the whole question
    the configure funnel asks, and ``skills`` had the same problem. The two
    ``*_decision`` fields answer it per half, in the one vocabulary
    :func:`consent.decision_reason` defines, so the halves can be compared.

    ``mcp_decision`` and ``detected`` are filled in by the caller that resolved
    that consent — this module does not decide anything, so it does not know
    them. ``skills_decision`` is set here, because this is where the pack is
    asked about.
    """

    clients: int
    skills: bool
    #: Which clients were registered, not only how many — the count cannot say
    #: which AI clients people actually pick.
    registered_clients: Tuple[str, ...] = ()
    failed_clients: int = 0
    verified: Optional[bool] = None
    detected: int = 0
    #: Which clients were on offer, filled in by the caller that detected them.
    detected_keys: Tuple[str, ...] = ()
    mcp_decision: Optional[str] = None
    skills_decision: Optional[str] = None
    #: The user reached the client picker and chose nothing — a refusal, not a
    #: run that never got as far as asking. The caller turns this into
    #: ``mcp_decision``; only the installer can tell the two apart.
    mcp_declined: bool = False
    #: Carried straight up from the installer: which server was registered, and
    #: whether its sign-in went through. The funnel cannot be joined to what the
    #: MCP server reported without the first, and cannot explain the drop to
    #: "connected" without the second.
    transport: Optional[str] = None
    sign_in: str = "not_attempted"
    #: The user cancelled at the picker. Carried so the caller can stop rather
    #: than treat it as a decision about the server alone.
    cancelled: bool = False
    #: Whether a stale `opik-mcp` uv tool install was in the way, and whether it
    #: could be cleared. One left behind pins the server at a version that may
    #: predate identity resolution.
    stale_tool: str = "absent"


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

    The skill pack is no longer a question: it is part of the setup, installed
    unless a flag refused it. See :func:`consent.resolve_installed_by_default`.

    ``install_mcp`` is already resolved: the question names the clients it would
    write to, so the caller asks it before this runs. ``skills`` arrives as a
    verdict, and a default one yields to a server refused at the picker.
    """
    # One view for the whole step, not one per half: it carries what the server
    # install learned — notably whether the connection needs a sign-in — through
    # to the closing block below, which is printed after the skill pack.
    view = install_view.RichInstallView()

    install = (
        mcp_installer.setup_mcp_server(
            **dict(setup_params),
            force_local_server=force_local_server,
            host_keys=host_keys,
            assume_confirmed=assume_confirmed,
            view=view,
        )
        if install_mcp
        else mcp_install.NOTHING_INSTALLED
    )
    # Ctrl-C is not an answer to the MCP question, it is "stop" — so nothing else
    # in this step runs. The pack used to be a separate question and survived a
    # cancel by being asked separately; now it is installed by default, and
    # carrying on would have meant a cancelled run still writing into the user's
    # AI client.
    if install.cancelled:
        return NOTHING_DONE._replace(
            transport=install.transport,
            sign_in=install.sign_in,
            stale_tool=install.stale_tool,
            mcp_declined=install.declined,
            # Not `declined`: nobody refused the pack, the run stopped before it
            # came up. The funnel has to be able to tell those apart.
            skills_decision=consent.Reason.CANCELLED.value,
            cancelled=True,
        )

    # "Skip" at the picker refused the server, and with it the only thing this
    # run had been asked to write into an AI client. A pack that is installed by
    # default follows that refusal, the rule `opik configure` applies to a "no" to
    # its MCP question; an explicit `--skills` is a request of its own and stands.
    if (
        install.declined
        and not install.manual
        and skills.reason is consent.Reason.INSTALLED_BY_DEFAULT
    ):
        skills = consent.Verdict(consent.Decision.SKIP, consent.Reason.DECLINED)

    configured_hosts = list(install.registered)

    # Where the pack goes: the clients we just registered, or — when the server
    # step was declined or skipped — whatever is on this machine.
    #
    # Except when the user picked "my AI client is not listed", where falling
    # back to every detected client would put the pack in the very ones they
    # just disowned. Naming none installs the shared copy and links nowhere,
    # which is the half an unlisted client can be pointed at by hand.
    #
    # Which clients each list can actually hold the pack is `setup_skills`'
    # business either way: it names the ones it could not place it in.
    if configured_hosts:
        skills_targets = configured_hosts
    elif install.manual:
        skills_targets = []
    else:
        skills_targets = skills_installer.detected_host_keys()

    installed_skills = False

    # `skills_installed` alone cannot say why it was false: a flag that refused
    # the pack and a download that failed look identical, and only one of them is
    # a problem.
    wants_skills = skills.decision is consent.Decision.PROCEED
    skills_reason = skills.reason.value

    if wants_skills:
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
        # Nothing landed, but a run where every write failed is not the same as one
        # where nothing was attempted, so the failure count rides along either way.
        return NOTHING_DONE._replace(
            registered_clients=install.registered,
            failed_clients=len(install.failed),
            verified=install.verified,
            skills_decision=skills_reason,
            mcp_declined=install.declined,
            transport=install.transport,
            sign_in=install.sign_in,
            stale_tool=install.stale_tool,
        )

    view.done(
        components, skills_roots.display_names(configured_hosts or skills_targets)
    )

    return Outcome(
        clients=len(configured_hosts),
        skills=installed_skills,
        registered_clients=install.registered,
        failed_clients=len(install.failed),
        verified=install.verified,
        skills_decision=skills_reason,
        mcp_declined=install.declined,
        transport=install.transport,
        sign_in=install.sign_in,
        stale_tool=install.stale_tool,
    )
