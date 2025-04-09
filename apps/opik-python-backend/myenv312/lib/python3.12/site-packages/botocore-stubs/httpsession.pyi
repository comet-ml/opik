"""
Type annotations for botocore.httpsession module.

Copyright 2025 Vlad Emelianov
"""

from logging import Logger
from typing import Any, Mapping

from botocore.awsrequest import AWSPreparedRequest, AWSRequest, AWSResponse
from botocore.compat import IPV6_ADDRZ_RE as IPV6_ADDRZ_RE
from botocore.compat import filter_ssl_warnings as filter_ssl_warnings
from botocore.compat import urlparse as urlparse
from botocore.exceptions import ConnectionClosedError as ConnectionClosedError
from botocore.exceptions import ConnectTimeoutError as ConnectTimeoutError
from botocore.exceptions import EndpointConnectionError as EndpointConnectionError
from botocore.exceptions import HTTPClientError as HTTPClientError
from botocore.exceptions import InvalidProxiesConfigError as InvalidProxiesConfigError
from botocore.exceptions import ProxyConnectionError as ProxyConnectionError
from botocore.exceptions import ReadTimeoutError as ReadTimeoutError
from botocore.exceptions import SSLError as SSLError

logger: Logger = ...

DEFAULT_TIMEOUT: int = ...
MAX_POOL_CONNECTIONS: int = ...
DEFAULT_CA_BUNDLE: str = ...
DEFAULT_CIPHERS: str | None = ...

def where() -> str: ...
def get_cert_path(verify: bool) -> str | None: ...
def create_urllib3_context(
    ssl_version: int | None = ...,
    cert_reqs: bool | None = ...,
    options: int | None = ...,
    ciphers: str | None = ...,
) -> Any: ...
def ensure_boolean(val: Any) -> bool: ...
def mask_proxy_url(proxy_url: str) -> str: ...

class ProxyConfiguration:
    def __init__(
        self,
        proxies: Mapping[str, Any] | None = ...,
        proxies_settings: Mapping[str, Any] | None = ...,
    ) -> None: ...
    def proxy_url_for(self, url: str) -> str: ...
    def proxy_headers_for(self, proxy_url: str) -> dict[str, Any]: ...
    @property
    def settings(self) -> dict[str, Any]: ...

class URLLib3Session:
    def __init__(
        self,
        verify: bool = ...,
        proxies: Mapping[str, Any] | None = ...,
        timeout: int | None = ...,
        max_pool_connections: int = ...,
        socket_options: list[str] | None = ...,
        client_cert: str | tuple[str, str] | None = ...,
        proxies_config: Mapping[str, Any] | None = ...,
    ) -> None: ...
    def close(self) -> None: ...
    def send(self, request: AWSRequest | AWSPreparedRequest) -> AWSResponse: ...
