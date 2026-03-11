from typing import Optional, Dict, Any

import httpx

from opik import config, httpx_client, url_helpers


def _get_httpx_client(
    api_key: Optional[str] = None, workspace: Optional[str] = None
) -> httpx.Client:
    config_ = config.OpikConfig()
    client = httpx_client.get(
        workspace=workspace,
        api_key=api_key,
        check_tls_certificate=config_.check_tls_certificate,
        compress_json_requests=config_.enable_json_request_compression,
    )

    return client


def list_user_permissions(api_key: str, workspace: str, url: str) -> Dict[str, Any]:
    try:
        with _get_httpx_client(api_key, workspace=workspace) as client:
            response = client.get(url=url_helpers.get_user_permissions_url(url))
    except httpx.RequestError as e:
        raise ConnectionError(f"Network error: {str(e)}")

    if response.status_code != 200:
        raise ConnectionError(f"HTTP error {response.status_code} - {response.text}")

    return response.json()
