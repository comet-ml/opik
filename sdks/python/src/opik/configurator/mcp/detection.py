"""Detect whether a configured Opik deployment runs a hosted MCP server.

The CLI prefers the Opik-hosted MCP server (HTTP + browser OAuth) and falls back
to the local ``uvx opik-mcp`` server only when no hosted one is available. The
hosted server is currently live on dev.comet.com and will reach Opik Cloud later;
self-hosted and open-source deployments generally have none. Rather than encode
that matrix, we probe the deployment for the capability, so a deployment that
gains the server later is picked up with no code change.
"""

import logging
import urllib.parse
from typing import Final, Optional

import opik.httpx_client as httpx_client
import opik.url_helpers as url_helpers

LOGGER = logging.getLogger(__name__)

WELL_KNOWN_OAUTH_PATH: Final[str] = ".well-known/oauth-authorization-server/opik"
MCP_ENDPOINT_PATH: Final[str] = "v1/mcp"
PROBE_TIMEOUT_SECONDS: Final[float] = 5.0


def detect_hosted_mcp_server(
    base_url: str, api_url: str, check_tls_certificate: bool
) -> Optional[str]:
    """Return the hosted Opik MCP server URL, or ``None`` if there is none.

    Probes the OAuth authorization-server metadata endpoint
    (``/.well-known/oauth-authorization-server/opik``) at the deployment root. A
    deployment running the MCP auth server serves valid OAuth metadata there; one
    that does not answers with a 404 or the frontend's HTML catch-all, in which
    case the caller falls back to the local ``uvx`` server.

    The MCP endpoint itself is the Opik REST base (``api_url``) plus ``v1/mcp``
    (e.g. ``https://dev.comet.com/opik/api/`` -> ``…/opik/api/v1/mcp``).

    ``check_tls_certificate`` comes from the already-loaded ``OpikConfig`` so this
    probe does not re-read configuration.
    """
    well_known_url = urllib.parse.urljoin(
        url_helpers.ensure_ending_slash(base_url), WELL_KNOWN_OAUTH_PATH
    )

    try:
        # The probe is a bodyless GET, so request compression does not apply.
        with httpx_client.get(
            workspace=None,
            api_key=None,
            check_tls_certificate=check_tls_certificate,
            compress_json_requests=False,
        ) as client:
            response = client.get(well_known_url, timeout=PROBE_TIMEOUT_SECONDS)
    except Exception:
        LOGGER.debug(
            "Could not probe for a hosted MCP server at %s",
            well_known_url,
            exc_info=True,
        )
        return None

    if response.status_code != 200:
        return None

    try:
        metadata = response.json()
    except Exception:
        # The frontend catch-all returns HTML (the SPA index) with a 200 status;
        # treat anything that is not OAuth metadata JSON as "no hosted server".
        return None

    if not isinstance(metadata, dict) or "authorization_endpoint" not in metadata:
        return None

    return urllib.parse.urljoin(
        url_helpers.ensure_ending_slash(api_url), MCP_ENDPOINT_PATH
    )
