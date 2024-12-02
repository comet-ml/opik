from typing import Optional, Dict, Any
import httpx

from . import hooks, package_version
import platform


def get(workspace: str, api_key: Optional[str]) -> httpx.Client:
    limits = httpx.Limits(keepalive_expiry=30)
    client = httpx.Client(limits=limits)

    headers = _prepare_headers(workspace=workspace, api_key=api_key)
    client.headers.update(headers)

    hooks.run_httpx_client_hooks(client)

    return client


def _prepare_headers(workspace: str, api_key: Optional[str]) -> Dict[str, Any]:
    result = {
        "Comet-Workspace": workspace,
        "X-OPIK-DEBUG-SDK-VERSION": package_version.VERSION,
        "X-OPIK-DEBUG-PY-VERSION": platform.python_version(),
    }

    if api_key is not None:
        result["Authorization"] = api_key

    return result
