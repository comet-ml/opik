from typing import Optional, Dict, Any, Union
import httpx
import os
from . import hooks, package_version
import platform


CABundlePath = str

KEEPALIVE_EXPIRY_SECONDS = 10
CONNECT_TIMEOUT_SECONDS = 20
READ_TIMEOUT_SECONDS = 100
WRITE_TIMEOUT_SECONDS = 100
POOL_TIMEOUT_SECONDS = 20


def get(
    workspace: Optional[str], api_key: Optional[str], check_tls_certificate: bool
) -> httpx.Client:
    limits = httpx.Limits(keepalive_expiry=KEEPALIVE_EXPIRY_SECONDS)

    verify: Union[bool, CABundlePath] = (
        os.environ["SSL_CERT_FILE"]
        if check_tls_certificate is True and "SSL_CERT_FILE" in os.environ
        else check_tls_certificate
    )

    timeout = httpx.Timeout(
        connect=CONNECT_TIMEOUT_SECONDS,
        read=READ_TIMEOUT_SECONDS,
        write=WRITE_TIMEOUT_SECONDS,
        pool=POOL_TIMEOUT_SECONDS,
    )

    client = httpx.Client(limits=limits, verify=verify, timeout=timeout)

    headers = _prepare_headers(workspace=workspace, api_key=api_key)
    client.headers.update(headers)

    hooks.run_httpx_client_hooks(client)

    return client


def _prepare_headers(
    workspace: Optional[str], api_key: Optional[str]
) -> Dict[str, Any]:
    result = {
        "X-OPIK-DEBUG-SDK-VERSION": package_version.VERSION,
        "X-OPIK-DEBUG-PY-VERSION": platform.python_version(),
    }

    if workspace is not None:
        result["Comet-Workspace"] = workspace

    if api_key is not None:
        result["Authorization"] = api_key

    return result
