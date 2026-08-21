"""Register the Opik MCP server with the user's AI host(s).

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
from typing import List, Optional, Tuple

import opik.config as opik_config
from opik.configurator import interactive_helpers
from opik.configurator.mcp import detection as mcp_detection
from opik.configurator.mcp import env as mcp_env
from opik.configurator.mcp import spec as mcp_spec
from opik.configurator.mcp import targets as mcp_targets
from opik.configurator.mcp import verification as mcp_verification

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
) -> None:
    """Register the Opik MCP server with the user's AI host(s).

    The decision of *whether* to run this lives in the callers; by the time this
    is called the user has opted in.

    ``check_tls_certificate`` and ``force_local_server`` are keyword-only with
    backward-compatible defaults, so the original positional call pattern
    (``api_key``, ``workspace``, ``base_url``, ``api_url``, ``use_local``,
    ``self_hosted_comet``) keeps working. ``force_local_server`` skips the
    hosted-server probe and always installs the local ``uvx`` server.

    ``host_keys`` names the hosts to install for explicitly, which is what makes
    this usable from CI, a Dockerfile, or a coding agent: given it, nothing is
    detected and nothing is prompted. ``assume_confirmed`` suppresses the target
    confirmation when the caller already showed the user a prompt naming the same
    hosts, so consent is collected once rather than twice.
    """
    ambiguity = _workspace_ambiguity(
        api_key=api_key,
        workspace=workspace,
        base_url=base_url,
        use_local=use_local,
        check_tls_certificate=check_tls_certificate,
    )
    if ambiguity is not None:
        LOGGER.warning(ambiguity)
        # ANALYTICS: install skipped, reason="ambiguous_workspace".
        return

    # Prefer the Opik-hosted MCP server when the deployment runs one; otherwise
    # fall back to the local `uvx opik-mcp` server. The probe — not the
    # deployment type — drives the choice, so a deployment that gains the hosted
    # server later is picked up with no code change.
    if force_local_server:
        hosted_mcp_url = None
    else:
        hosted_mcp_url = mcp_detection.detect_hosted_mcp_server(
            base_url=base_url,
            api_url=api_url,
            check_tls_certificate=check_tls_certificate,
        )
    if hosted_mcp_url is not None:
        LOGGER.info("Found a hosted Opik MCP server; configuring AI host(s) to use it.")
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
        LOGGER.warning(unavailable_reason)
        # ANALYTICS: install skipped, reason="uv_missing".
        return

    # ANALYTICS: install started. Carries the transport ("http" for the hosted
    # server, "stdio" for uvx), the deployment class, and how many hosts were
    # detected — the denominator every later stage is measured against.
    selected_targets = _resolve_targets(
        host_keys=host_keys,
        assume_confirmed=assume_confirmed,
        server_spec=server_spec,
    )
    if len(selected_targets) == 0:
        # ANALYTICS: install skipped. `_resolve_targets` already logged which of
        # "declined" / "no_host_detected" / "unknown_host" applies; that reason is
        # the single most important number here, since it is the decline rate on
        # the consent prompt and is currently unobservable.
        return

    if isinstance(server_spec, mcp_spec.StdioServerSpec):
        _prefetch_opik_mcp()

    results = [target.install(server_spec) for target in selected_targets]
    _report_results(results)

    # One verification per run: it exercises the credentials, which are identical
    # for every host, so running it once and reporting once is enough.
    if any(result.succeeded for result in results):
        verification = _verify(
            server_spec=server_spec,
            api_key=api_key,
            workspace=workspace,
            api_url=api_url,
            check_tls_certificate=check_tls_certificate,
        )
        _report_verification(verification)

    # ANALYTICS: one install completed/failed event per host in `selected_targets`,
    # labelled with `target.key`, the transport, and the verification outcome.
    # Per-host rather than per-run: a run that writes Cursor and fails Codex is two
    # different facts.


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


def _resolve_targets(
    host_keys: Optional[List[str]],
    assume_confirmed: bool,
    server_spec: mcp_spec.McpServerSpec,
) -> List[mcp_targets.HostTarget]:
    """Decide which hosts to install for.

    An explicit ``host_keys`` bypasses detection entirely: naming a host is the
    caller stating a fact, and requiring the host to be installed first would
    defeat the point in a Dockerfile or a fresh CI image.
    """
    if host_keys:
        explicit: List[mcp_targets.HostTarget] = []
        for key in host_keys:
            target = mcp_targets.find_target(key)
            if target is None:
                # Unreachable through the CLI, which validates against HOST_KEYS;
                # reachable from a direct library call.
                LOGGER.warning(
                    "Unknown AI host '%s'. Known hosts: %s",
                    key,
                    ", ".join(mcp_targets.HOST_KEYS),
                )
                continue
            explicit.append(target)
        return explicit

    detected_targets = mcp_targets.detected_targets()

    if len(detected_targets) == 0:
        _log_manual_instructions(server_spec)
        return []

    if assume_confirmed:
        return detected_targets

    selected = _select_targets(detected_targets)
    if len(selected) == 0:
        LOGGER.info(
            "Skipped MCP server setup. Run `opik mcp configure` anytime to set it up."
        )
    return selected


def _log_manual_instructions(server_spec: mcp_spec.McpServerSpec) -> None:
    block = mcp_spec.redact_block_for_display(server_spec.to_block())
    manual_config = json.dumps({"mcpServers": {"opik-mcp": block}}, indent=2)
    LOGGER.info(
        "No supported AI host (%s) was detected.\n"
        "To set it up manually, add this to your host's MCP config "
        '(VS Code uses "servers" instead of "mcpServers"):\n%s\n'
        "Or name a host directly: `opik mcp configure --host claude-code`.\n"
        "See %s for per-host instructions.",
        ", ".join(target.display_name for target in mcp_targets.HOST_TARGETS),
        manual_config,
        MCP_DOCS_URL,
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


def _report_verification(result: mcp_verification.VerificationResult) -> None:
    if result.succeeded:
        LOGGER.info("Verified: %s.", result.detail)
        LOGGER.info(
            "Restart your AI host to pick up the Opik MCP server, then ask it to "
            "'list my Opik projects'."
        )
        return

    LOGGER.warning(
        "The Opik MCP server was registered, but verification failed: %s",
        result.detail,
    )


def _prefetch_opik_mcp() -> None:
    """Download opik-mcp now so the AI host connects instantly on first launch.

    Hosts run ``uvx opik-mcp``, which otherwise fetches the package and a
    Python 3.13 interpreter lazily on first use — slow, and any failure surfaces
    as an opaque host error. ``uv tool install`` warms uv's cache and validates
    the whole chain up front. Best-effort: a failure here is not fatal, the host
    will still fetch on demand.
    """
    uv_executable = shutil.which("uv")
    if uv_executable is None:
        return

    LOGGER.info("Pre-fetching the Opik MCP server (uv tool install opik-mcp)...")
    # Stream uv's own output (download/build progress, and any error detail)
    # straight to the terminal rather than capturing it — the install can take a
    # while, and hiding its logs leaves the user staring at a frozen prompt.
    try:
        result = subprocess.run([uv_executable, "tool", "install", "opik-mcp"])
    except OSError as error:
        LOGGER.warning(
            "Could not pre-fetch opik-mcp: %s. Your AI host will download it on "
            "first use instead.",
            error,
        )
        return

    if result.returncode != 0:
        LOGGER.warning(
            "Could not pre-fetch opik-mcp (`uv tool install opik-mcp` exited %s, "
            "see its output above). Your AI host will download it on first use "
            "instead.",
            result.returncode,
        )


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


def _select_targets(
    detected_targets: List[mcp_targets.HostTarget],
) -> List[mcp_targets.HostTarget]:
    """Ask the user which detected host(s) to install for.

    A single detected host is a simple yes/no confirm. With more than one, a
    numbered menu doubles as the list of detected hosts and accepts a single
    number, a comma-separated list (e.g. ``1,2``), "All", or "Skip".
    """
    if len(detected_targets) == 1:
        target = detected_targets[0]
        confirmed = interactive_helpers.ask_user_for_approval(
            f"Detected {target.display_name}. Install the Opik MCP server for it? (Y/n) "
        )
        return [target] if confirmed else []

    host_count = len(detected_targets)
    all_choice = host_count + 1
    skip_choice = host_count + 2

    lines = ["Which AI host(s) should the Opik MCP server be installed for?"]
    for index, target in enumerate(detected_targets, start=1):
        lines.append(f"  {index} - {target.display_name}")
    lines.append(f"  {all_choice} - All of the above")
    lines.append(f"  {skip_choice} - Skip")
    lines.append("\nEnter a number, or several separated by commas (e.g. 1,2)\n> ")
    prompt = "\n".join(lines)

    while True:
        choices = [token.strip() for token in input(prompt).split(",") if token.strip()]

        if not choices or not all(token.isdigit() for token in choices):
            LOGGER.error("Wrong choice. Please try again.\n")
            continue

        numbers = [int(token) for token in choices]

        if skip_choice in numbers:
            return []
        if all_choice in numbers:
            return list(detected_targets)
        if all(1 <= number <= host_count for number in numbers):
            return [detected_targets[number - 1] for number in dict.fromkeys(numbers)]

        LOGGER.error("Wrong choice. Please try again.\n")


def _report_results(results: List[mcp_targets.InstallResult]) -> None:
    for result in results:
        if result.succeeded:
            LOGGER.info("%s: %s", result.target_display_name, result.detail)
        else:
            LOGGER.warning("%s: %s", result.target_display_name, result.detail)
