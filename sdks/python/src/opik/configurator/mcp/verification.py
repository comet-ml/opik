"""Prove a freshly written MCP registration actually works.

Writing a host config file is not evidence of a working setup: an ``opik-mcp``
server with no credentials starts happily, reports the ``default`` workspace and
advertises its full tool list, so a completely unconfigured install is
indistinguishable from a working one until the user asks a question and gets a
401. That failure lands inside a chat transcript, which is the worst place to
debug credentials.

So the installer finishes by making a real call with exactly the values it just
wrote, and reports what came back. What "works" means depends on the transport:

- Local (stdio): the credentials live in the host config's ``env`` block, so we
  can exercise them directly against the Opik REST API.
- Hosted (HTTP + OAuth): nothing to exercise — the AI host performs the browser
  flow on first connect. We only confirm the endpoint is there and challenges an
  unauthenticated caller, which is the healthy answer.
"""

import dataclasses
import logging
from typing import Final, List, Optional

import httpx

import opik.httpx_client as httpx_client
import opik.url_helpers as url_helpers

LOGGER = logging.getLogger(__name__)

VERIFY_TIMEOUT_SECONDS: Final[float] = 10.0


@dataclasses.dataclass
class VerificationResult:
    succeeded: bool
    detail: str


def _client(
    api_key: Optional[str], workspace: Optional[str], check_tls: bool
) -> httpx.Client:
    return httpx_client.get(
        workspace=workspace,
        api_key=api_key,
        check_tls_certificate=check_tls,
        compress_json_requests=False,
    )


def verify_local_credentials(
    api_key: Optional[str],
    workspace: Optional[str],
    api_url: str,
    check_tls_certificate: bool,
) -> VerificationResult:
    """List projects with the credentials just written to the host config."""
    try:
        with _client(api_key, workspace, check_tls_certificate) as client:
            response = client.get(
                url=f"{url_helpers.ensure_ending_slash(api_url)}v1/private/projects",
                params={"page": 1, "size": 1},
                timeout=VERIFY_TIMEOUT_SECONDS,
            )
    except (httpx.HTTPError, OSError) as error:
        return VerificationResult(
            succeeded=False,
            detail=(
                f"could not reach {api_url} ({error}). Your AI client will hit the "
                "same failure — check the URL and your network, then re-run "
                "`opik mcp configure`."
            ),
        )

    if response.status_code in (401, 403):
        return VerificationResult(
            succeeded=False,
            detail=(
                f"Opik rejected the credentials written to your host config "
                f"(HTTP {response.status_code}). Run `opik configure` to refresh "
                "your API key and workspace, then re-run `opik mcp configure`."
            ),
        )

    if response.status_code != 200:
        return VerificationResult(
            succeeded=False,
            detail=(
                f"Opik returned HTTP {response.status_code} for a project listing. "
                "The MCP server is registered but may not work; re-run "
                "`opik mcp status` once the deployment is healthy."
            ),
        )

    project_count = _project_count(response)
    workspace_label = workspace or "default"
    if project_count is None:
        return VerificationResult(
            succeeded=True,
            detail=f"connected to workspace {workspace_label}",
        )
    return VerificationResult(
        succeeded=True,
        detail=(
            f"connected to workspace {workspace_label}, "
            f"{project_count} project(s) visible"
        ),
    )


def _project_count(response: httpx.Response) -> Optional[int]:
    """Total projects from a page-1 listing, or ``None`` if unparseable.

    A missing count is not a verification failure — the call itself already
    proved the credentials work, so we just report less.
    """
    try:
        payload = response.json()
    except ValueError:
        return None

    if not isinstance(payload, dict):
        return None

    total = payload.get("total")
    if isinstance(total, int):
        return total

    content = payload.get("content")
    return len(content) if isinstance(content, list) else None


def verify_hosted_endpoint(
    mcp_url: str, check_tls_certificate: bool
) -> VerificationResult:
    """Confirm the hosted MCP endpoint exists and challenges unauthenticated calls.

    401/403 is the expected, healthy response: no credentials are stored in the
    host config, so an unauthenticated probe *must* be rejected. A 404 means the
    endpoint we just registered is not there.
    """
    try:
        with _client(None, None, check_tls_certificate) as client:
            response = client.get(url=mcp_url, timeout=VERIFY_TIMEOUT_SECONDS)
    except (httpx.HTTPError, OSError) as error:
        return VerificationResult(
            succeeded=False,
            detail=(
                f"could not reach {mcp_url} ({error}). Your AI client will hit the "
                "same failure when it tries to sign in."
            ),
        )

    if response.status_code == 404:
        return VerificationResult(
            succeeded=False,
            detail=(
                f"{mcp_url} returned HTTP 404 — the hosted MCP server is not "
                "available at that address. Re-run with `--local-server` to use "
                "the local server instead."
            ),
        )

    # Only the auth challenge proves a *working* endpoint. Treating everything
    # except 404 as success meant a 500 or a 502 reported "reachable", and the
    # user found out when their client failed to sign in.
    if response.status_code not in (401, 403):
        return VerificationResult(
            succeeded=False,
            detail=(
                f"{mcp_url} returned HTTP {response.status_code}, not the sign-in "
                "challenge a healthy hosted server answers with. The server is "
                "registered but may not work; check the deployment, then re-run "
                "`opik mcp status`."
            ),
        )

    return VerificationResult(
        succeeded=True,
        detail=(
            f"hosted server reachable at {mcp_url}; your AI client will prompt you "
            "to sign in through the browser on first connect"
        ),
    )


def list_workspaces(
    api_key: str, base_url: str, check_tls_certificate: bool
) -> Optional[List[str]]:
    """Workspace names on the account, or ``None`` if they cannot be determined.

    ``None`` means "we do not know" and must never be treated as "one workspace":
    the caller uses this to decide whether an unspecified workspace is ambiguous,
    and guessing there is exactly the failure mode this guards against.
    """
    try:
        with _client(api_key, None, check_tls_certificate) as client:
            response = client.get(
                url=url_helpers.get_workspace_list_url(base_url),
                timeout=VERIFY_TIMEOUT_SECONDS,
            )
    except (httpx.HTTPError, OSError):
        LOGGER.debug("Could not list workspaces for %s", base_url, exc_info=True)
        return None

    if response.status_code != 200:
        return None

    try:
        payload = response.json()
    except ValueError:
        return None

    if not isinstance(payload, dict):
        return None

    names = payload.get("workspaceNames")
    if not isinstance(names, list):
        return None

    return [str(name) for name in names]
