"""Configure command for Opik CLI."""

import os
import urllib.parse
from typing import Any, Mapping, Optional

import click

import opik.config as opik_config
from opik import analytics
from opik.cli import account_identity
from opik.cli import assistants
from opik.cli import install_view
from opik.cli import status_view
from opik.configurator import consent
from opik.configurator import configure as opik_configure, interactive_helpers
from opik.configurator import mcp as mcp_installer


def _setup_assistants(
    setup_params: Mapping[str, Any],
    install_mcp: Optional[bool],
    install_skills: Optional[bool],
    automatic_approvals: bool,
) -> assistants.Outcome:
    """The CLI's assistant step: resolve consent, then hand off to the installers.

    Both questions go through :func:`consent.resolve`, so this function no longer
    holds a policy of its own — it wires the flags to it and does the asking.
    """
    interactive = interactive_helpers.is_interactive()
    detected = mcp_installer.detected_host_keys()
    situation = dict(
        assume_yes=automatic_approvals,
        interactive=interactive,
        anything_detected=len(detected) > 0,
    )

    mcp_verdict = consent.resolve(install_mcp, **situation)
    skills_verdict = consent.resolve(install_skills, **situation)

    wants_mcp = consent.granted(mcp_verdict, _ask_about_mcp)

    mcp_decision = consent.decision_reason(mcp_verdict, wants_mcp)

    # Only when the pack was skipped for a reason of its own. Declining the
    # server no longer declines the pack: they were coupled because the MCP
    # question read as the umbrella for the whole step, but the pack needs no
    # server — it is instruction files for clients that are already there — so
    # one Enter was dropping a second thing the user never said no to.
    if not wants_mcp and skills_verdict.decision is consent.Decision.SKIP:
        _announce_skip(mcp_verdict, skills_verdict)
        # The pack was never asked about on this path, so its decision comes
        # straight off the verdict — nobody said no, the question never arose.
        return assistants.NOTHING_DONE._replace(
            detected=len(detected),
            detected_keys=tuple(detected),
            mcp_decision=mcp_decision,
            skills_decision=skills_verdict.reason.value,
        )

    outcome = assistants.setup(
        setup_params,
        install_mcp=wants_mcp,
        skills=skills_verdict,
        # A named flag covers whatever is detected, so it needs no picker. A yes to
        # the prompt above does not: the picker is where a subset can be chosen,
        # and its first row is "All", which is where the cursor starts — so the
        # second step costs a keystroke rather than a decision, and Enter no
        # longer silently takes whichever client happened to be listed first.
        assume_confirmed=mcp_verdict.reason is consent.Reason.REQUESTED,
    )
    # `mcp_decision` answers the permission question and nothing else. Folding a
    # skipped picker into it relabelled those runs as never having accepted,
    # which hid the one drop the funnel exists to show: said yes, then chose no
    # client. That drop is `clients_written == 0` after `requested`, and
    # `mcp_declined` says whether it was deliberate.
    # `skills_decision` is left as `setup` recorded it: it did the asking.
    return outcome._replace(
        detected=len(detected),
        detected_keys=tuple(detected),
        mcp_decision=mcp_decision,
    )


def _ask_about_mcp() -> bool:
    """Ask before touching any assistant's configuration.

    `opik configure` writes ``~/.opik.config``, which is Opik's own file.
    Registering the MCP server writes into files owned by Cursor, Claude Code and
    friends, and that is a different kind of permission — so it is asked for
    outright rather than inferred from the client picker, which only asks
    *which*. Escaping out of a picker is not a legible "no, just configure Opik".

    The picker still follows, because "whether" and "which" are two questions and
    only the first of them is about permission. `opik mcp configure` asks only
    the second: running that command is itself the answer to this one.

    Defaults to yes — it is recommended, and the block above says what it is. A
    real label, because the empty one this replaced turned Enter into a silent
    refusal of a question that never looked like one.
    """
    install_view.render_mcp_intro()
    return click.confirm("  Set up Opik MCP?", default=True)


#: Skips worth mentioning, and how to say them. A skip the user asked for
#: (``--no-install-mcp``) or one with nothing to act on needs no explanation;
#: these two look like the command silently did less than it was asked to.
_SKIP_NOTES = {
    consent.Reason.NO_TERMINAL: (
        "Skipped AI client setup: no terminal to ask in, and no --install-mcp or "
        "--install-skills flag was passed."
    ),
    consent.Reason.ASSUME_YES: (
        "Skipped AI client setup: -y answers Opik's own questions, and does not "
        "write to another tool's configuration."
    ),
    consent.Reason.NOTHING_DETECTED: (
        "Skipped AI client setup: none of the supported AI clients were found on "
        "this machine."
    ),
}


def _announce_skip(
    mcp_verdict: consent.Verdict, skills_verdict: consent.Verdict
) -> None:
    """Say that the assistant step was skipped, and how to include it.

    Staying silent reported "configuration completed successfully" to a caller
    that had also asked for the MCP server, with no way to notice the difference
    between "configured Opik" and "configured Opik and your client".

    The reason comes from the verdict rather than being inferred here. Inferring it
    is how an unattended run — which has no terminal *and* is handed ``-y`` by the
    command — ended up being told that ``-y`` was why, having never passed it.
    """
    note = _SKIP_NOTES.get(mcp_verdict.reason) or _SKIP_NOTES.get(skills_verdict.reason)
    if note is None:
        return

    install_view.render_note(
        note, "To include it:  opik configure --install-mcp --install-skills"
    )


#: One line of context per deployment. The names and the numbers stay in
#: ``DeploymentType``, which the library prompt renders from too, so the two
#: cannot drift apart.
_DEPLOYMENT_BLURBS = {
    interactive_helpers.DeploymentType.CLOUD: "Managed by Comet, free to start",
    interactive_helpers.DeploymentType.SELF_HOSTED: "Your organisation's own Comet platform",
    interactive_helpers.DeploymentType.LOCAL: "An Opik you run yourself",
}


def _ask_for_deployment_type() -> interactive_helpers.DeploymentType:
    """The deployment question, rendered by the CLI rather than the configurator.

    Presentation only. The answer is still read by
    ``interactive_helpers.ask_user_for_deployment_type``, so the accepted input
    is what it has always been — ``1``, ``2``, ``3``, or Enter for the default —
    and a script piping those in does not care that the question got a headline.
    ``configurator`` keeps its plain-text version because ``opik.configure()`` is
    a library call and must not take over the caller's terminal.

    ``rich`` drops the styling by itself when stdout is not a terminal, so a
    redirected or styling-less terminal gets the same words without escapes.
    """
    question = "Where should Opik log your traces?"
    rows = [
        (str(deployment.value[0]), deployment.value[1], _DEPLOYMENT_BLURBS[deployment])
        for deployment in interactive_helpers.DeploymentType
    ]

    if install_view.can_pick():
        chosen = install_view.choose_one_numbered(question, rows)
        if chosen is None:
            # Escape or Ctrl-C. Aborting is what Ctrl-C did at the `input()`
            # prompt this replaces; falling through would re-ask the question
            # the user just backed out of.
            raise click.Abort()
        return interactive_helpers.DeploymentType.find_by_value(int(chosen))

    # No picker here — a pipe, a CI log, a terminal without raw-mode key reading.
    # The numbered prompt is the original question, unchanged, so the answers are
    # the same ones and nothing driving this from a script notices a difference.
    install_view.render_numbered_choices(question, rows)
    return interactive_helpers.ask_user_for_deployment_type(
        prompt="  Enter 1, 2 or 3 [1]: "
    )


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
        return _ask_for_deployment_type()

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


class Progress:
    """Milestones carried back to the click command, which reports them.

    The command is the only frame that *can*: ``analytics`` drops any event
    raised inside a frame that has already reported one, so the flow cannot emit
    as it goes — a nested ``track_event`` never reaches the worker. Mutated in
    place rather than returned, because the value of it is on the path where
    there is no return: an exception leaves the last stage reached behind.
    """

    #: How far the run got. Ordered, so the funnel reads as a sequence.
    DEPLOYMENT = "deployment"
    CREDENTIALS = "credentials"
    ASSISTANTS = "assistants"
    DONE = "done"

    def __init__(self) -> None:
        self.stage = self.DEPLOYMENT
        self.deployment: Optional[str] = None


def run_interactive_configure(
    use_local: bool = False,
    automatic_approvals: bool = False,
    install_mcp: Optional[bool] = None,
    install_skills: Optional[bool] = None,
    progress: Optional[Progress] = None,
) -> assistants.Outcome:
    """Programmatic entry to the interactive ``opik configure`` flow.

    Reused by ``opik connect`` / ``opik endpoint`` so they can auto-launch
    configuration when no ~/.opik.config is present.

    Returns what the assistant step did, so the command that owns the analytics
    event can report it. The configurator takes the step as a callback and
    discards its return value, hence the recorder rather than a plain return.
    """
    recorded = assistants.NOTHING_DONE
    # A throwaway when the caller did not supply one, so the milestones are
    # recorded the same way whether or not anybody is reading them.
    progress = progress if progress is not None else Progress()

    def record(*args: Any) -> None:
        nonlocal recorded
        # Reached only once the credentials are written, so this is also what
        # says the run got past them.
        progress.stage = Progress.ASSISTANTS
        recorded = _setup_assistants(*args)

    if use_local:
        # `--use_local` answers the deployment question, so it is never asked.
        progress.deployment = interactive_helpers.DeploymentType.LOCAL.name.lower()
        progress.stage = Progress.CREDENTIALS
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
            announce=install_view.render_hint,
        ).configure()
        progress.stage = Progress.DONE
        return recorded

    deployment_type_choice = _deployment_type()
    progress.deployment = deployment_type_choice.name.lower()
    progress.stage = Progress.CREDENTIALS

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
            announce=install_view.render_hint,
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
            announce=install_view.render_hint,
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
            announce=install_view.render_hint,
        )
    else:
        raise click.ClickException("Unknown deployment type was selected. Exiting.")

    configurator.configure()
    progress.stage = Progress.DONE

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
    interactive = interactive_helpers.is_interactive()
    # `-y` and "no terminal" both mean "do not ask me", and both suppress the AI
    # client step outright — so the funnel needs to see it. It was invisible.
    automatic_approvals = yes or not interactive

    analytics.track_event(
        "configuration",
        "configure",
        interactive=interactive,
        automatic_approvals=automatic_approvals,
        # The tri-states as passed, so "asked and said yes" is separable from
        # "never asked" — the flag is also how an agent drives this.
        install_mcp=str(install_mcp),
        install_skills=str(install_skills),
        # Whoever is already configured, if anyone: a first-ever run has no
        # credential yet at this point, and says so.
        **account_identity.event_properties(),
    )

    # With no terminal there is nobody to ask, and every question here has a sane
    # default: use the local instance we found, keep the project name we derived.
    # Demanding `-y` to say "yes, the defaults" was a step that existed only to be
    # discovered — and the error teaching it was the step an agent was most likely
    # to stop at.
    progress = Progress()
    try:
        outcome = run_interactive_configure(
            use_local=use_local,
            automatic_approvals=automatic_approvals,
            install_mcp=install_mcp,
            install_skills=install_skills,
            progress=progress,
        )
    except BaseException as exception:
        # One in five runs used to end here and report nothing at all, so the
        # entry event had no sibling and the drop was indistinguishable from a
        # user who simply never finished. Only the exception's type is reported:
        # its message can carry a URL, a workspace or a key.
        analytics.track_event(
            "configuration",
            "configure",
            "failed",
            interactive=interactive,
            automatic_approvals=automatic_approvals,
            error_type=type(exception).__name__,
            # Which question it died at. `error_type` alone says a run ended, not
            # whether the user walked away from the deployment picker, the API
            # key, or the AI client step — three very different problems.
            stage=progress.stage,
            deployment=progress.deployment,
            **account_identity.event_properties(),
        )
        raise

    # Sibling of the entry event above, reported from this same frame. Entry says
    # what was asked for, this says what was actually written — the gap between
    # the two is the drop-off worth watching.
    analytics.track_event(
        "configuration",
        "configure",
        "result",
        clients_written=outcome.clients,
        clients_failed=outcome.failed_clients,
        skills_installed=outcome.skills,
        # What makes a zero readable: how many clients there were to write to,
        # what the user decided about each half, and whether what we wrote
        # actually works. Without them every zero looks like a refusal.
        detected_clients=outcome.detected,
        mcp_decision=outcome.mcp_decision,
        skills_decision=outcome.skills_decision,
        verification_succeeded=outcome.verified,
        # Which clients, not only how many. Sorted and joined so one string is a
        # stable breakdown value, and `splitByChar` gets back to per-client
        # counts — the two together say what was on offer and what was taken.
        clients_detected=",".join(sorted(outcome.detected_keys)),
        clients_registered=",".join(sorted(outcome.registered_clients)),
        # Why a run that accepted still wrote nothing: the user chose no client,
        # rather than the installer failing or being blocked before it asked.
        picker_skipped=outcome.mcp_declined,
        # Carried onto the result too: the entry event has it, and a funnel whose
        # steps filter on different things is not measuring one population.
        interactive=interactive,
        # The first thing the interactive flow asks, and it was invisible: the
        # funnel could not tell a cloud run from a local one except by guessing
        # from an identity property resolved afterwards.
        deployment=progress.deployment,
        stage=progress.stage,
        # Resolved again rather than reused from the entry event: this is the run
        # that just wrote ~/.opik.config, so it is the first point at which a
        # first-ever configure has an account to name at all.
        **account_identity.event_properties(),
    )


@configure.command(name="status")
def status() -> None:
    """Show the active Opik configuration: file path, environment, and workspace."""
    status_view.render_config_summary(opik_config.OpikConfig())
