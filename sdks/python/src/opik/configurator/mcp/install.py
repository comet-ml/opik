import json
import logging
import shutil
import subprocess
from typing import List, Optional, Tuple

from opik.configurator import interactive_helpers
from opik.configurator.mcp import env as mcp_env
from opik.configurator.mcp import spec as mcp_spec
from opik.configurator.mcp import targets as mcp_targets

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
) -> None:
    """Register the Opik MCP server with the user's detected AI host(s).

    The decision of *whether* to run this lives in the configurator; by the time
    this is called the user has opted in and the session is interactive.
    """
    available_modes = _available_connection_modes(
        use_local=use_local, self_hosted_comet=self_hosted_comet
    )
    # Only one mode exists today. Once the Opik Cloud-hosted server ships,
    # `available_modes` may contain more than one entry for cloud users, and the
    # user would be asked to choose here.
    connection_mode = available_modes[0]

    server_spec, unavailable_reason = _create_server_spec(
        connection_mode=connection_mode,
        api_key=api_key,
        workspace=workspace,
        base_url=base_url,
        api_url=api_url,
        use_local=use_local,
        self_hosted_comet=self_hosted_comet,
    )
    if server_spec is None:
        LOGGER.warning(unavailable_reason)
        return

    detected_targets = [
        target for target in mcp_targets.HOST_TARGETS if target.is_detected()
    ]
    if len(detected_targets) == 0:
        manual_config = json.dumps(
            {
                "mcpServers": {
                    "opik-mcp": mcp_spec.redact_block_for_display(
                        server_spec.to_block()
                    )
                }
            },
            indent=2,
        )
        LOGGER.info(
            "No supported AI host (Claude Code, Cursor, VS Code) was detected.\n"
            "To set it up manually, add this to your host's MCP config "
            '(VS Code uses "servers" instead of "mcpServers"):\n%s\n'
            "See %s for per-host instructions.",
            manual_config,
            MCP_DOCS_URL,
        )
        return

    selected_targets = _select_targets(detected_targets)
    if len(selected_targets) == 0:
        LOGGER.info(
            "Skipped MCP server setup. Run `opik mcp install` anytime to set it up."
        )
        return

    if isinstance(server_spec, mcp_spec.StdioServerSpec):
        _prefetch_opik_mcp()

    results = [target.install(server_spec) for target in selected_targets]
    _report_results(results)


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
    result = subprocess.run(
        [uv_executable, "tool", "install", "opik-mcp"],
        capture_output=True,
        text=True,
    )
    if result.returncode != 0:
        LOGGER.warning(
            "Could not pre-fetch opik-mcp: %s. Your AI host will download it on "
            "first use instead.",
            result.stderr.strip() or "`uv tool install opik-mcp` failed",
        )


def _available_connection_modes(
    use_local: bool,
    self_hosted_comet: bool,
) -> List[mcp_spec.McpConnectionMode]:
    """Connection modes valid for the configured deployment.

    Localhost and self-hosted deployments always run the MCP server locally, so
    only ``LOCAL_STDIO`` applies. The Opik Cloud-hosted (browser-OAuth) server is
    relevant to Opik Cloud only: when it ships, ``McpConnectionMode.REMOTE`` would
    be added here for the cloud case (``not use_local and not self_hosted_comet``).
    """
    return [mcp_spec.McpConnectionMode.LOCAL_STDIO]


def _create_server_spec(
    connection_mode: mcp_spec.McpConnectionMode,
    api_key: Optional[str],
    workspace: Optional[str],
    base_url: str,
    api_url: str,
    use_local: bool,
    self_hosted_comet: bool,
) -> Tuple[Optional[mcp_spec.McpServerSpec], Optional[str]]:
    """Build the spec for the chosen connection mode.

    Returns ``(spec, None)`` on success, or ``(None, reason)`` when prerequisites
    for that mode are missing. A future remote/OAuth mode would add a branch here
    and would not depend on ``uvx``.
    """
    if connection_mode is mcp_spec.McpConnectionMode.LOCAL_STDIO:
        uvx_executable = shutil.which("uvx")
        if uvx_executable is None:
            return None, (
                "The Opik MCP server runs via `uvx`, which was not found on your "
                f"PATH. Install uv ({UV_INSTALL_DOCS_URL}), then run `opik mcp "
                "install` to set it up. uvx fetches opik-mcp and a compatible "
                "Python automatically."
            )

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

    if any(result.succeeded for result in results):
        LOGGER.info(
            "Restart your AI host to pick up the Opik MCP server, then ask it to "
            "'list my Opik projects' to confirm the connection."
        )
