import gzip
from typing import Optional, Dict, Any, Union, Iterable, AsyncIterable, Mapping
import httpx
import os
import json as jsonlib

from . import hooks, package_version
import platform


CABundlePath = str

KEEPALIVE_EXPIRY_SECONDS = 10
CONNECT_TIMEOUT_SECONDS = 20
READ_TIMEOUT_SECONDS = 100
WRITE_TIMEOUT_SECONDS = 100
POOL_TIMEOUT_SECONDS = 20


def get(
    workspace: Optional[str],
    api_key: Optional[str],
    check_tls_certificate: bool,
    compress_json_requests: bool,
) -> httpx.Client:
    limits = httpx.Limits(keepalive_expiry=KEEPALIVE_EXPIRY_SECONDS)

    verify: Union[bool, CABundlePath] = (
        os.environ["SSL_CERT_FILE"]
        if check_tls_certificate is True and "SSL_CERT_FILE" in os.environ
        else check_tls_certificate
    )
    # we need this to enable proxy server to analyze the request/response session during debugging
    proxy = os.environ.get("_OPIK_HTTP_PROXY")

    timeout = httpx.Timeout(
        connect=CONNECT_TIMEOUT_SECONDS,
        read=READ_TIMEOUT_SECONDS,
        write=WRITE_TIMEOUT_SECONDS,
        pool=POOL_TIMEOUT_SECONDS,
    )

    client = OpikHttpxClient(
        compress_json_requests=compress_json_requests,
        limits=limits,
        verify=verify,
        timeout=timeout,
        follow_redirects=True,
        proxy=proxy,
    )

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
        "Accept-Encoding": "gzip",
    }

    if workspace is not None:
        result["Comet-Workspace"] = workspace

    if api_key is not None:
        result["Authorization"] = api_key

    return result


class OpikHttpxClient(httpx.Client):
    def __init__(self, compress_json_requests: bool = True, **kwargs: Any) -> None:
        super().__init__(**kwargs)
        self.compress_json_requests = compress_json_requests

    def build_request(
        self,
        method: str,
        url: Union[httpx.URL, str],
        *,
        content: Optional[
            Union[str, bytes, Iterable[bytes], AsyncIterable[bytes]]
        ] = None,
        data: Optional[Mapping[str, Any]] = None,
        files: Any = None,
        json: Any = None,
        params: Any = None,
        headers: Any = None,
        cookies: Any = None,
        timeout: Any = httpx.USE_CLIENT_DEFAULT,
        extensions: Any = None,
    ) -> httpx.Request:
        # we override this method to allow compression of JSON requests that is handled
        # by httpx.Client.request() as well as by httpx.Client.stream() (both used in the OPIK)
        if self.compress_json_requests:
            if method in ("POST", "PUT", "PATCH") and json is not None:
                json_data = jsonlib.dumps(json).encode("utf-8")
                content = gzip.compress(json_data)
                json = None
                if headers is None:
                    headers = {}
                headers["Content-Length"] = str(len(content))
                headers["Content-Encoding"] = "gzip"
                if "content-type" not in headers:
                    # to avoid having it in headers two times with different cases in keys (e.g., streaming operations)
                    headers["Content-Type"] = "application/json;charset=utf-8"

        return super().build_request(
            method=method,
            url=url,
            content=content,
            data=data,
            files=files,
            json=json,
            params=params,
            headers=headers,
            cookies=cookies,
            timeout=timeout,
            extensions=extensions,
        )
