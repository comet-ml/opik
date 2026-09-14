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
    # Local (uvx) registrations only: whether the recorded command asks for a
    # version. Without one, an `opik-mcp` installed as a uv tool wins and the
    # client starts that version forever. ``None`` for a hosted registration,
    # which runs no local package at all.
    requests_latest: Optional[bool] = None


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
    status.requests_latest = _requests_latest(block)

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


def _requests_latest(block: Dict[str, Any]) -> bool:
    """Whether a recorded stdio block asks uv for a version of ``opik-mcp``.

    Reads ``args`` and ``command`` both: most hosts split the executable from its
    arguments, while opencode records one ``command`` list holding both.
    """
    words: List[str] = []
    for key in ("args", "command"):
        value = block.get(key)
        if isinstance(value, list):
            words.extend(str(item) for item in value)
    return mcp_spec.PACKAGE_REQUEST in words


def uv_tool_install_note(host_statuses: List[HostStatus]) -> Optional[str]:
    """What to say about an ``opik-mcp`` installed as a uv tool, if anything.

    Only meaningful alongside a local (uvx) registration — a hosted server runs no
    local package, so an install on the same machine is beside the point.

    The two cases read very differently to the person on the other end, so they
    are worded differently. A registration with no version request is *currently
    frozen* on the installed version and has a fix; one that asks for
    ``@latest`` is fine, and the install is merely shadowing a command they might
    type themselves. Neither is phrased as an error: a deliberate pin is rare but
    real, and this is the only signal that distinguishes it from the accident.
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

    frozen = [host.display_name for host in local_hosts if not host.requests_latest]
    if len(frozen) > 0:
        return (
            f"opik-mcp {installed} is installed as a uv tool, and "
            f"{', '.join(frozen)} still launches it as `uvx opik-mcp` with no "
            f"version — so it starts {installed} every time, whatever has been "
            f"released since. Re-run `opik mcp configure` to update the "
            f"registration."
        )

    return (
        f"opik-mcp {installed} is installed as a uv tool. Your registrations ask "
        f"for `{mcp_spec.PACKAGE_REQUEST}`, so the MCP server is unaffected — but "
        f"that install shadows a bare `uvx opik-mcp` you run yourself. `uv tool "
        f"upgrade opik-mcp` updates it; `uv tool uninstall opik-mcp` removes it."
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
