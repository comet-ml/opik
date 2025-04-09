"""
Type annotations for botocore.awsrequest module.

Copyright 2025 Vlad Emelianov
"""

from collections.abc import MutableMapping
from logging import Logger
from typing import IO, Any, Iterator, Mapping, TypeVar

from botocore.compat import HTTPHeaders as HTTPHeaders
from botocore.compat import HTTPResponse as HTTPResponse
from botocore.compat import urlencode as urlencode
from botocore.compat import urlsplit as urlsplit
from botocore.compat import urlunsplit as urlunsplit
from botocore.exceptions import UnseekableStreamError as UnseekableStreamError
from urllib3.connection import HTTPConnection, VerifiedHTTPSConnection
from urllib3.connectionpool import HTTPConnectionPool, HTTPSConnectionPool

_R = TypeVar("_R")

logger: Logger = ...

class AWSHTTPResponse(HTTPResponse):
    def __init__(self, *args: Any, **kwargs: Any) -> None: ...

class AWSConnection:
    def __init__(self, *args: Any, **kwargs: Any) -> None:
        self.response_class: type[AWSHTTPResponse]

    def close(self) -> None: ...
    def request(
        self,
        method: str,
        url: str,
        body: str | bytes | bytearray | IO[bytes] | IO[str] | None = ...,
        headers: Mapping[str, Any] | None = ...,
        *args: Any,
        **kwargs: Any,
    ) -> HTTPConnection: ...
    def send(self, str: str) -> Any: ...

# FIXME: AWSConnection.send has incompatible arg names
class AWSHTTPConnection(AWSConnection, HTTPConnection): ...  # type: ignore [misc]
class AWSHTTPSConnection(AWSConnection, VerifiedHTTPSConnection): ...  # type: ignore [misc]

class AWSHTTPConnectionPool(HTTPConnectionPool):
    ConnectionCls: type[AWSHTTPConnection]  # type: ignore [misc,assignment]

class AWSHTTPSConnectionPool(HTTPSConnectionPool):
    ConnectionCls: type[AWSHTTPSConnection]  # type: ignore [misc,assignment]

def prepare_request_dict(
    request_dict: Mapping[str, Any],
    endpoint_url: str,
    context: Mapping[str, Any] | None = ...,
    user_agent: str | None = ...,
) -> None: ...
def create_request_object(request_dict: Mapping[str, Any]) -> Any: ...

class AWSPreparedRequest:
    def __init__(
        self,
        method: str,
        url: str,
        headers: HTTPHeaders,
        body: str | bytes | bytearray | IO[bytes] | IO[str] | None,
        stream_output: bool,
    ) -> None:
        self.method: str
        self.url: str
        self.headers: HTTPHeaders
        self.body: str | bytes | bytearray | IO[bytes] | IO[str] | None
        self.stream_output: bool

    def reset_stream(self) -> None: ...

class AWSRequest:
    def __init__(
        self,
        method: str | None = ...,
        url: str | None = ...,
        headers: Mapping[str, Any] | None = ...,
        data: Any | None = ...,
        params: Mapping[str, Any] | None = ...,
        auth_path: str | None = ...,
        stream_output: bool = ...,
    ) -> None:
        self.method: str | None
        self.url: str | None
        self.headers: HTTPHeaders
        self.data: Any | None
        self.params: dict[str, Any]
        self.auth_path: str | None
        self.stream_output: bool
        self.context: dict[str, Any]

    def prepare(self) -> AWSPreparedRequest: ...
    @property
    def body(self) -> str: ...

class AWSRequestPreparer:
    def prepare(self, original: AWSRequest) -> AWSPreparedRequest: ...

class AWSResponse:
    def __init__(self, url: str, status_code: int, headers: HTTPHeaders, raw: Any) -> None:
        self.url: str
        self.status_code: int
        self.headers: HeadersDict
        self.raw: Any

    @property
    def content(self) -> bytes: ...
    @property
    def text(self) -> str: ...

class _HeaderKey:
    def __init__(self, key: str) -> None: ...
    def __hash__(self) -> int: ...
    def __eq__(self, other: object) -> bool: ...

class HeadersDict(MutableMapping[str, str]):
    def __init__(self, *args: Any, **kwargs: Any) -> None: ...
    def __setitem__(self, key: str, value: Any) -> None: ...
    def __getitem__(self, key: str) -> Any: ...
    def __delitem__(self, key: str) -> None: ...
    def __iter__(self) -> Iterator[str]: ...
    def __len__(self) -> int: ...
    def copy(self: _R) -> _R: ...
