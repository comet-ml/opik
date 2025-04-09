"""
Type annotations for botocore.crt.auth module.

Copyright 2025 Vlad Emelianov
"""

from typing import Protocol

from botocore.auth import SIGNED_HEADERS_BLACKLIST as SIGNED_HEADERS_BLACKLIST
from botocore.auth import STREAMING_UNSIGNED_PAYLOAD_TRAILER as STREAMING_UNSIGNED_PAYLOAD_TRAILER
from botocore.auth import UNSIGNED_PAYLOAD as UNSIGNED_PAYLOAD
from botocore.auth import BaseSigner as BaseSigner
from botocore.awsrequest import AWSRequest
from botocore.compat import HTTPHeaders as HTTPHeaders
from botocore.compat import parse_qs as parse_qs
from botocore.compat import urlsplit as urlsplit
from botocore.compat import urlunsplit as urlunsplit
from botocore.exceptions import NoCredentialsError as NoCredentialsError
from botocore.utils import percent_encode_sequence as percent_encode_sequence

class _Credentials(Protocol):
    access_key: str
    secret_key: str
    token: str | None

class CrtSigV4Auth(BaseSigner):
    REQUIRES_REGION: bool = ...

    def __init__(
        self,
        credentials: _Credentials,
        service_name: str,
        region_name: str,
    ) -> None:
        self.credentials: _Credentials = ...

    def add_auth(self, request: AWSRequest) -> None: ...

class CrtS3SigV4Auth(CrtSigV4Auth): ...

class CrtSigV4AsymAuth(BaseSigner):
    REQUIRES_REGION: bool = ...

    def __init__(
        self,
        credentials: _Credentials,
        service_name: str,
        region_name: str,
    ) -> None:
        self.credentials: _Credentials = ...

    def add_auth(self, request: AWSRequest) -> None: ...

class CrtS3SigV4AsymAuth(CrtSigV4AsymAuth): ...

class CrtSigV4AsymQueryAuth(CrtSigV4AsymAuth):
    DEFAULT_EXPIRES: int = ...

    def __init__(
        self,
        credentials: _Credentials,
        service_name: str,
        region_name: str,
        expires: int = ...,
    ) -> None: ...

class CrtS3SigV4AsymQueryAuth(CrtSigV4AsymQueryAuth): ...

class CrtSigV4QueryAuth(CrtSigV4Auth):
    DEFAULT_EXPIRES: int = ...
    def __init__(
        self,
        credentials: _Credentials,
        service_name: str,
        region_name: str,
        expires: int = ...,
    ) -> None: ...

class CrtS3SigV4QueryAuth(CrtSigV4QueryAuth): ...

CRT_AUTH_TYPE_MAPS: dict[str, type[BaseSigner]] = ...
