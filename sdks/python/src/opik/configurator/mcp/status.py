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
from opik.configurator.mcp import targets as mcp_targets

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


def _api_url_from_mcp_url(mcp_url: str) -> str:
    """Strip the ``v1/mcp`` endpoint suffix to recover the Opik REST base."""
    trimmed = mcp_url.rstrip("/")
    suffix = "/v1/mcp"
    if trimmed.endswith(suffix):
        trimmed = trimmed[: -len(suffix)]
    return trimmed


def _normalize_url(url: Optional[str]) -> str:
    return (url or "").rstrip("/")
