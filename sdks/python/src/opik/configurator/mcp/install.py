"""Register the Opik MCP server with the user's AI client(s).

Analytics note — the installer is currently unmeasured. Every MCP metric we have
is emitted by the ``opik-mcp`` server, which can only report once it exists, so
the earliest observable moment is ``server_started`` — after configuration has
already succeeded. People who tried to set Opik up and gave up are therefore
invisible. The reporting seam is being built on a separate branch; the
``ANALYTICS:`` comments below mark each call site and the event it owes, so wiring
it up is a mechanical change rather than a re-reading of this flow.
"""

import json
import logging
import shutil
import subprocess
import sys
from typing import Final, List, Optional, Tuple

import opik.config as opik_config
from opik.configurator import interactive_helpers
from opik.configurator.mcp import detection as mcp_detection
from opik.configurator.mcp import env as mcp_env
from opik.configurator.mcp import spec as mcp_spec
from opik.configurator.mcp import targets as mcp_targets
from opik.configurator.mcp import verification as mcp_verification
from opik.configurator.mcp import view as mcp_view

LOGGER = logging.getLogger(__name__)

UV_INSTALL_DOCS_URL = "https://docs.astral.sh/uv/"
MCP_DOCS_URL = "https://www.comet.com/docs/opik/mcp-server"


def setup_mcp_server(
    api_key: Optional[str],
    workspace: Optional[str],
    base_url: str,
    api_url: str,
    use_local: bool,
    self_hosted_comet: bool,
    *,
    check_tls_certificate: bool = True,
    force_local_server: bool = False,
    host_keys: Optional[List[str]] = None,
    assume_confirmed: bool = False,
    view: Optional[mcp_view.InstallView] = None,
    announce_next_steps: bool = True,
) -> List[str]:
    """Register the Opik MCP server with the user's AI client(s).

    The decision of *whether* to run this lives in the callers; by the time this
    is called the user has opted in.

    ``check_tls_certificate`` and ``force_local_server`` are keyword-only with
    backward-compatible defaults, so the original positional call pattern
    (``api_key``, ``workspace``, ``base_url``, ``api_url``, ``use_local``,
    ``self_hosted_comet``) keeps working. ``force_local_server`` skips the
    hosted-server probe and always installs the local ``uvx`` server.

    ``host_keys`` names the AI clients to install for explicitly, which skips
    detection and the picker — but not the terminal requirement above.
    ``assume_confirmed`` suppresses the target confirmation when the caller
    already showed the user a prompt naming the same clients, so consent is
    collected once rather than twice.

    ``view`` decides how the flow narrates itself; it defaults to the logger so
    that ``opik.configure()`` stays library-safe. The CLI passes a ``rich`` view.


    Returns the host keys actually registered, so a caller can act on the same set
    without asking the user a second time.
    """
    display = view if view is not None else mcp_view.default_view()

    # The backstop for every caller, library included: without a terminal we can
    # only proceed on an explicit request, and `host_keys` is what one looks like.
    # That is what lets a coding agent run this on the user's behalf while a CI
    # job that named nothing still does nothing.
    if (
        not interactive_helpers.is_interactive()
        and not host_keys
        and not assume_confirmed
    ):
        display.skipped(
            "Skipping MCP server setup: no interactive terminal and no client "
            "named. Pass `--ai-client <client>` to set it up unattended, or run "
            "`opik mcp configure` from a shell."
        )
        return []

    ambiguity = _workspace_ambiguity(
        api_key=api_key,
        workspace=workspace,
        base_url=base_url,
        use_local=use_local,
        check_tls_certificate=check_tls_certificate,
    )
    if ambiguity is not None:
        display.problem(ambiguity)
        return []

    # Prefer the Opik-hosted MCP server when the deployment runs one; otherwise
    # fall back to the local `uvx opik-mcp` server. The probe — not the
    # deployment type — drives the choice, so a deployment that gains the hosted
    # server later is picked up with no code change.
    if force_local_server:
        hosted_mcp_url = None
    else:
        with display.step("Looking for a hosted Opik MCP server"):
            hosted_mcp_url = mcp_detection.detect_hosted_mcp_server(
                base_url=base_url,
                api_url=api_url,
                check_tls_certificate=check_tls_certificate,
            )
    if hosted_mcp_url is not None:
        connection_mode = mcp_spec.McpConnectionMode.REMOTE
    else:
        connection_mode = mcp_spec.McpConnectionMode.LOCAL_STDIO

    server_spec, unavailable_reason = _create_server_spec(
        connection_mode=connection_mode,
        hosted_mcp_url=hosted_mcp_url,
        api_key=api_key,
        workspace=workspace,
        base_url=base_url,
        api_url=api_url,
        use_local=use_local,
        self_hosted_comet=self_hosted_comet,
    )
    if server_spec is None:
        display.problem(unavailable_reason or "")
        return []

    candidates = _candidate_targets(host_keys)
    if len(candidates) == 0:
        if host_keys:
            display.problem(
                f"None of the requested AI clients are known. Known clients: "
                f"{', '.join(mcp_targets.HOST_KEYS)}."
            )
        else:
            _report_no_host_detected(server_spec, display)
        return []

    # Shown before anything is written, and before the confirmation below, so the
    # user is consenting to a change they can see rather than a yes/no in the dark.
    display.plan(
        deployment=_deployment_label(use_local, self_hosted_comet, workspace),
        transport=_transport_label(server_spec),
        # Only the hosted server has a sign-in step, and how it gets triggered is
        # the host's choice, not ours: some open the browser on first use, others
        # leave the server unauthorized until asked. An unauthorized server
        # contributes no tools at all rather than an error, so a user who is not
        # told to look never finds out why it went quiet. The view carries this
        # to its closing block, which `cli.assistants` prints after the skill
        # pack — by then the spec is out of scope.
        needs_sign_in=isinstance(server_spec, mcp_spec.RemoteServerSpec),
        targets=[
            mcp_view.PlannedTarget(
                display_name=target.display_name,
                location=_target_location(target, server_spec),
            )
            for target in candidates
        ],
    )

    selected_targets = _confirm_targets(
        candidates, host_keys, assume_confirmed, display
    )
    if len(selected_targets) == 0:
        display.skipped(
            "Skipped MCP server setup. Run `opik mcp configure` anytime to set it up."
        )
        return []

    if isinstance(server_spec, mcp_spec.StdioServerSpec):
        with display.step("Preparing the Opik MCP server"):
            _prefetch_opik_mcp()

    results = [target.install(server_spec) for target in selected_targets]
    display.results(
        [
            mcp_view.TargetResult(
                display_name=result.target_display_name,
                detail=result.detail,
                succeeded=result.succeeded,
                summary=result.summary,
            )
            for result in results
        ]
    )

    # One verification per run: it exercises the credentials, which are identical
    # for every host, so running it once and reporting once is enough.
    if any(result.succeeded for result in results):
        with display.step("Checking the connection"):
            verification = _verify(
                server_spec=server_spec,
                api_key=api_key,
                workspace=workspace,
                api_url=api_url,
                check_tls_certificate=check_tls_certificate,
            )
        display.verification(verification.succeeded, verification.detail)
        if verification.succeeded and announce_next_steps:
            display.done(
                ["MCP server"],
                [result.target_display_name for result in results if result.succeeded],
            )

    return [
        target.key
        for target, result in zip(selected_targets, results)
        if result.succeeded
    ]


def _deployment_label(
    use_local: bool, self_hosted_comet: bool, workspace: Optional[str]
) -> str:
    """A one-line answer to "which Opik am I being connected to?"."""
    if use_local:
        return "Local Opik"
    environment = "Self-hosted Comet" if self_hosted_comet else "Opik Cloud"
    return f"{environment} · workspace {workspace}" if workspace else environment


def _transport_label(server_spec: mcp_spec.McpServerSpec) -> str:
    if isinstance(server_spec, mcp_spec.RemoteServerSpec):
        return "Hosted server, browser sign-in on first connect"
    return "Local server via uvx, credentials in the host config"


def _target_location(
    target: mcp_targets.HostTarget, server_spec: mcp_spec.McpServerSpec
) -> str:
    """Where this host's registration will land, in the user's own terms."""
    if target.key == "claude-code" and shutil.which("claude") is not None:
        return "via `claude mcp add`"
    if target.key == "codex":
        return "via `codex mcp add`"
    return mcp_view.display_path(target.config_path())


def _workspace_ambiguity(
    api_key: Optional[str],
    workspace: Optional[str],
    base_url: str,
    use_local: bool,
    check_tls_certificate: bool,
) -> Optional[str]:
    """Explain why the workspace is too ambiguous to write, or ``None`` if it is fine.

    Omitting the workspace makes ``opik-mcp`` send ``default``, which the backend
    resolves to the account's default workspace. On an account with several
    workspaces that does not fail — it quietly reads from the wrong place, which
    is worse than any error this installer can produce. So when the workspace was
    never chosen and the account has more than one, refuse rather than guess.

    An unknown workspace list counts as "not ambiguous": we block on positive
    evidence of ambiguity only, never on a failed lookup.
    """
    if use_local or not api_key:
        return None
    if workspace and workspace != opik_config.OPIK_WORKSPACE_DEFAULT_NAME:
        return None

    workspaces = mcp_verification.list_workspaces(
        api_key=api_key,
        base_url=base_url,
        check_tls_certificate=check_tls_certificate,
    )
    if workspaces is None or len(workspaces) <= 1:
        return None

    return (
        "Your Opik configuration does not name a workspace, but this account has "
        f"{len(workspaces)}: {', '.join(sorted(workspaces))}. The MCP server would "
        "fall back to your default workspace and silently read from the wrong "
        "place. Run `opik configure` and choose a workspace, then re-run "
        "`opik mcp configure`."
    )


def _candidate_targets(
    host_keys: Optional[List[str]],
) -> List[mcp_targets.HostTarget]:
    """The hosts this run could install for, before asking the user anything.

    Split from the confirmation so the plan — including the exact files — can be
    shown *before* the prompt. An explicit ``host_keys`` bypasses detection
    entirely: naming a client is the caller stating a fact, so a client that is
    installed but not yet detectable can still be configured.
    """
    if host_keys:
        explicit: List[mcp_targets.HostTarget] = []
        for key in host_keys:
            target = mcp_targets.find_target(key)
            if target is None:
                # Unreachable through the CLI, which validates against HOST_KEYS;
                # reachable from a direct library call.
                LOGGER.debug("Unknown AI client %r requested", key)
                continue
            explicit.append(target)
        return explicit

    return mcp_targets.detected_targets()


def _confirm_targets(
    candidates: List[mcp_targets.HostTarget],
    host_keys: Optional[List[str]],
    assume_confirmed: bool,
    display: mcp_view.InstallView,
) -> List[mcp_targets.HostTarget]:
    """Narrow the candidates to what the user actually agreed to.

    Naming hosts explicitly, or having already been asked by the caller, is the
    agreement — so neither re-prompts.
    """
    if host_keys or assume_confirmed:
        return candidates

    chosen = display.choose_hosts(
        title="Which AI client should the Opik MCP server be set up for?",
        candidates=[
            mcp_view.HostChoice(key=target.key, label=target.display_name)
            for target in candidates
        ],
        # Nothing pre-ticked. Registering a server edits another tool's config
        # file, so Enter must not do it to every assistant found on the machine
        # by default — the same reason `opik configure -y` refuses to. Enter
        # takes the highlighted row; `a` is there when the answer really is all.
        preselected=[],
    )
    if chosen is None:
        return []
    by_key = {target.key: target for target in candidates}
    return [by_key[key] for key in chosen if key in by_key]


def _report_no_host_detected(
    server_spec: mcp_spec.McpServerSpec, display: mcp_view.InstallView
) -> None:
    block = mcp_spec.redact_block_for_display(server_spec.to_block())
    manual_config = json.dumps({"mcpServers": {"opik-mcp": block}}, indent=2)
    display.problem(
        f"No supported AI client was detected "
        f"({', '.join(target.display_name for target in mcp_targets.HOST_TARGETS)}).\n\n"
        f"Name one directly:\n"
        f"    opik mcp configure --ai-client claude-code\n\n"
        f"Or add this to your host's MCP config by hand "
        f'(VS Code uses "servers" instead of "mcpServers"):\n{manual_config}\n\n'
        f"See {MCP_DOCS_URL} for per-host instructions."
    )


def _verify(
    server_spec: mcp_spec.McpServerSpec,
    api_key: Optional[str],
    workspace: Optional[str],
    api_url: str,
    check_tls_certificate: bool,
) -> mcp_verification.VerificationResult:
    if isinstance(server_spec, mcp_spec.RemoteServerSpec):
        return mcp_verification.verify_hosted_endpoint(
            mcp_url=server_spec.url,
            check_tls_certificate=check_tls_certificate,
        )
    return mcp_verification.verify_local_credentials(
        api_key=api_key,
        workspace=workspace,
        api_url=api_url,
        check_tls_certificate=check_tls_certificate,
    )


#: Long enough for a cold fetch of the package and an interpreter, short enough
#: that a wedged download does not hold `opik configure` open indefinitely.
PREFETCH_TIMEOUT_SECONDS: Final[int] = 120


def _prefetch_opik_mcp() -> None:
    """Warm uv's cache so the AI client connects instantly on first launch.

    Clients run ``uvx opik-mcp``, which otherwise fetches the package and a
    Python interpreter lazily on first use — slow, and any failure surfaces as an
    opaque client error. So this runs the same thing the client will, which is
    what makes it a cache warm.

    Not ``uv tool install opik-mcp``, which was doing more than warming a cache:
    it builds a persistent tool environment and puts an ``opik-mcp`` shim on the
    user's PATH — an install into their environment that nothing announced, and
    one that silently keeps an older copy if there already was one. ``uv tool
    run`` populates the cache that the registered command actually reads.

    Output is captured rather than streamed because the caller runs this inside a
    rich status spinner: both write to the same terminal, and uv's progress bars
    and the spinner's redraw overwrite each other. The spinner is the progress
    signal; uv's own text is kept for the failure message.

    Best-effort throughout — a failure here is not fatal, the client will fetch on
    demand.
    """
    uv_executable = shutil.which("uv")
    if uv_executable is None:
        return

    try:
        result = subprocess.run(
            [uv_executable, "tool", "run", "opik-mcp", "--help"],
            capture_output=True,
            text=True,
            # Nothing should prompt here, and if it does the timeout must win
            # rather than leaving the spinner spinning on unread input.
            stdin=subprocess.DEVNULL,
            timeout=PREFETCH_TIMEOUT_SECONDS,
        )
    except subprocess.TimeoutExpired:
        LOGGER.warning(
            "Gave up pre-fetching opik-mcp after %ss. Your AI client will download "
            "it on first use instead.",
            PREFETCH_TIMEOUT_SECONDS,
        )
        return
    except OSError as error:
        LOGGER.warning(
            "Could not pre-fetch opik-mcp: %s. Your AI client will download it on "
            "first use instead.",
            error,
        )
        return

    if result.returncode != 0:
        LOGGER.warning(
            "Could not pre-fetch opik-mcp (exited %s: %s). Your AI client will "
            "download it on first use instead.",
            result.returncode,
            _last_line(result.stderr or result.stdout),
        )


#: Tool output goes into a log line, so it is trimmed to something readable.
_MAX_DETAIL_CHARS: Final[int] = 200


def _last_line(output: str) -> str:
    """The most useful line of a tool's output, bounded.

    Slicing with ``[-1:]`` here handed `LOGGER.warning` a one-element *list*, so
    the warning rendered as ``['network error']``.
    """
    lines = (output or "").strip().splitlines()
    return lines[-1][:_MAX_DETAIL_CHARS] if lines else "no output"


def _uv_install_hint() -> str:
    """The exact command to install uv on this platform.

    The local server cannot run at all without `uv`, so pointing at a docs page is
    one indirection too many at the moment setup stops — name the command.
    """
    if sys.platform == "win32":
        command = (
            "powershell -ExecutionPolicy ByPass "
            '-c "irm https://astral.sh/uv/install.ps1 | iex"'
        )
    else:
        command = "curl -LsSf https://astral.sh/uv/install.sh | sh"
    return (
        "The Opik MCP server runs via `uvx`, which was not found on your PATH.\n"
        f"Install uv with:\n    {command}\n"
        f"(see {UV_INSTALL_DOCS_URL}), then run `opik mcp configure`. uvx fetches "
        "opik-mcp and a compatible Python automatically."
    )


def _create_server_spec(
    connection_mode: mcp_spec.McpConnectionMode,
    hosted_mcp_url: Optional[str],
    api_key: Optional[str],
    workspace: Optional[str],
    base_url: str,
    api_url: str,
    use_local: bool,
    self_hosted_comet: bool,
) -> Tuple[Optional[mcp_spec.McpServerSpec], Optional[str]]:
    """Build the spec for the chosen connection mode.

    Returns ``(spec, None)`` on success, or ``(None, reason)`` when prerequisites
    for that mode are missing. The remote (hosted/OAuth) mode has no local
    prerequisites; the local stdio mode requires ``uvx`` on the PATH.
    """
    if connection_mode is mcp_spec.McpConnectionMode.REMOTE:
        assert hosted_mcp_url is not None  # guaranteed by the caller's probe
        return mcp_spec.RemoteServerSpec(url=hosted_mcp_url), None

    if connection_mode is mcp_spec.McpConnectionMode.LOCAL_STDIO:
        uvx_executable = shutil.which("uvx")
        if uvx_executable is None:
            return None, _uv_install_hint()

        server_env = mcp_env.build_mcp_env(
            api_key=api_key,
            workspace=workspace,
            base_url=base_url,
            api_url=api_url,
            use_local=use_local,
            self_hosted_comet=self_hosted_comet,
        )
        return (
            mcp_spec.StdioServerSpec(
                command=uvx_executable,
                args=["opik-mcp"],
                env=server_env,
            ),
            None,
        )

    raise ValueError(f"Unsupported MCP connection mode: {connection_mode}")
