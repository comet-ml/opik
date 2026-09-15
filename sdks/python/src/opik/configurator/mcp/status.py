"""Read-only inspection of where the Opik MCP server is configured per AI host.

Each AI host (Claude Code, Cursor, VS Code) keeps its own MCP config, written at
install time from the Opik config that was active *then* and not kept in sync with
``~/.opik.config`` afterwards. This module reports what each host currently points
at and flags any that disagree with the current Opik config; it never writes.
"""

import dataclasses
import pathlib
from typing import Any, Dict, List, Optional

import opik.config as opik_config
from opik.configurator.mcp import spec as mcp_spec
from opik.configurator.mcp import targets as mcp_targets
from opik.configurator.mcp import uv_tool

# The Opik REST base implied by a local (uvx) server block that carries an API
# key but no URL override — i.e. an Opik Cloud target.
CLOUD_API_URL = "https://www.comet.com/opik/api/"

TRANSPORT_HOSTED = "Hosted (HTTP + OAuth)"
TRANSPORT_HOSTED_SSE = "Hosted (SSE)"
TRANSPORT_LOCAL = "Local (uvx)"


@dataclasses.dataclass
class HostStatus:
    display_name: str
    config_path: pathlib.Path
    detected: bool
    registered: bool
    transport: Optional[str] = None
    points_to: Optional[str] = None
    workspace: Optional[str] = None
    in_sync: Optional[bool] = None
    # Local (uvx) registrations only: whether the recorded command escapes an
    # `opik-mcp` installed as a uv tool. Without that, such an install can win,
    # leaving the client starting that version indefinitely. ``None`` for a hosted
    # registration, which runs no local package at all.
    bypasses_tool_install: Optional[bool] = None


def collect_host_statuses(config: opik_config.OpikConfig) -> List[HostStatus]:
    """Inspect every known AI host and report its Opik MCP registration."""
    current_api_url = _normalize_url(config.url_override)
    current_workspace = config.workspace

    statuses: List[HostStatus] = []
    for target in mcp_targets.HOST_TARGETS:
        block = mcp_targets.read_registered_block(target)
        status = HostStatus(
            display_name=target.display_name,
            config_path=target.config_path(),
            detected=target.is_detected(),
            registered=block is not None,
        )
        if block is not None:
            _describe_block(status, block, current_api_url, current_workspace)
        statuses.append(status)
    return statuses


def _describe_block(
    status: HostStatus,
    block: Dict[str, Any],
    current_api_url: str,
    current_workspace: Optional[str],
) -> None:
    """Fill in transport, target, and sync state from a recorded server block."""
    block_type = block.get("type")
    if block_type in ("http", "sse") or "url" in block:
        # Branch on the recorded transport: only an HTTP server is the Opik-hosted
        # OAuth one. An SSE registration (hand-written) is reported as SSE rather
        # than mislabeled. `url` with no type defaults to HTTP.
        url = str(block.get("url", ""))
        status.transport = (
            TRANSPORT_HOSTED_SSE if block_type == "sse" else TRANSPORT_HOSTED
        )
        status.points_to = url
        status.workspace = None  # chosen during the OAuth flow, not stored here
        status.in_sync = _normalize_url(_api_url_from_mcp_url(url)) == current_api_url
        return

    env = block.get("env") or {}
    status.transport = TRANSPORT_LOCAL
    status.workspace = env.get("COMET_WORKSPACE")
    status.bypasses_tool_install = _bypasses_tool_install(block)

    if "OPIK_URL" in env:
        api_url = str(env["OPIK_URL"])
        status.points_to = api_url
    elif "COMET_URL_OVERRIDE" in env:
        base = str(env["COMET_URL_OVERRIDE"]).rstrip("/")
        api_url = f"{base}/opik/api/"
        status.points_to = base
    else:
        api_url = CLOUD_API_URL
        status.points_to = "Opik Cloud"

    workspace_in_sync = (
        status.workspace is None or status.workspace == current_workspace
    )
    status.in_sync = _normalize_url(api_url) == current_api_url and workspace_in_sync


def _bypasses_tool_install(block: Dict[str, Any]) -> bool:
    """Whether a recorded stdio block escapes an ``opik-mcp`` uv tool install.

    Two things do: the ``--isolated`` flag this installer writes, and any version
    request (``opik-mcp@latest``, ``opik-mcp==1.2.3``) — uv uses an installed tool
    "unless a version is requested". A hand-pinned config is therefore not
    reported as frozen, because it is not.

    Reads ``args`` and ``command`` both: most hosts split the executable from its
    arguments, while opencode records one ``command`` list holding both.
    """
    words: List[str] = []
    for key in ("args", "command"):
        value = block.get(key)
        if isinstance(value, list):
            words.extend(str(item) for item in value)
    if "--isolated" in words:
        return True
    return any(
        word.startswith(mcp_spec.SERVER_NAME) and word != mcp_spec.SERVER_NAME
        for word in words
    )


def uv_tool_install_note(host_statuses: List[HostStatus]) -> Optional[str]:
    """What to say about an ``opik-mcp`` installed as a uv tool, if anything.

    Only meaningful alongside a local (uvx) registration — a hosted server runs no
    local package, so an install on the same machine is beside the point.

    The two cases read very differently to the person on the other end, so they
    are worded differently. A registration that cannot escape the install is *at
    risk of* starting that version forever and has a fix; one that can is fine, and
    the install can at most take precedence over a bare ``uvx opik-mcp`` they type
    themselves.

    Both are hedged rather than asserted. uv reusing an existing tool environment
    is the normal case — reproduced on uv 0.8.12 and 0.11.7 alike — but not a
    certainty: at least one real environment was found being re-resolved past for
    reasons never established. Claiming a freeze the reader can disprove in one
    command would cost the message its credibility. Neither is phrased as an error
    either: a deliberate pin is rare but real, and this is the only signal that
    distinguishes it from the accident.
    """
    installed = uv_tool.installed_version()
    if installed is None:
        return None

    local_hosts = [
        host
        for host in host_statuses
        if host.registered and host.transport == TRANSPORT_LOCAL
    ]
    if len(local_hosts) == 0:
        return None

    frozen = [
        host.display_name for host in local_hosts if not host.bypasses_tool_install
    ]
    if len(frozen) > 0:
        return (
            f"opik-mcp {installed} is installed as a uv tool, and "
            f"{', '.join(frozen)} still launches it as a bare `uvx opik-mcp` with no "
            f"escape — so it may start {installed} every time, whatever has been "
            f"released since. Re-run `opik mcp configure` to update the "
            f"registration."
        )

    return (
        f"opik-mcp {installed} is installed as a uv tool. Your registrations run "
        f"`uvx --isolated opik-mcp`, so the MCP server is unaffected — but that "
        f"install can still take precedence over a bare `uvx opik-mcp` you run "
        f"yourself. `uv tool upgrade opik-mcp` updates it; `uv tool uninstall "
        f"opik-mcp` removes it."
    )


def _api_url_from_mcp_url(mcp_url: str) -> str:
    """Strip the ``v1/mcp`` endpoint suffix to recover the Opik REST base."""
    trimmed = mcp_url.rstrip("/")
    suffix = "/v1/mcp"
    if trimmed.endswith(suffix):
        trimmed = trimmed[: -len(suffix)]
    return trimmed


def _normalize_url(url: Optional[str]) -> str:
    return (url or "").rstrip("/")
