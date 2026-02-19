"""Pairing authentication - calls backend to connect runner."""

import logging
from typing import Dict, Any

import httpx

from opik.config import OpikConfig

LOGGER = logging.getLogger(__name__)


def connect_with_pairing_code(
    pairing_code: str,
    runner_name: str,
) -> Dict[str, Any]:
    """Call the backend's connect endpoint with a pairing code.

    Returns dict with runner_id, auth_token, redis_url, workspace_id.
    """
    config = OpikConfig()
    base_url = config.url_override.rstrip("/")
    url = f"{base_url}/v1/private/runners/connect"

    headers = {
        "Content-Type": "application/json",
        "Comet-Workspace": config.workspace,
    }

    if config.api_key:
        headers["Authorization"] = config.api_key

    payload = {
        "pairing_code": pairing_code,
        "runner_name": runner_name,
    }

    LOGGER.debug("Connecting to %s", url)

    response = httpx.post(url, json=payload, headers=headers, timeout=30.0)
    response.raise_for_status()

    return response.json()
