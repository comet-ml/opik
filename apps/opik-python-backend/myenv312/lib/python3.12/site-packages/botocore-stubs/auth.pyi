"""
Type annotations for botocore.auth module.

Copyright 2025 Vlad Emelianov
"""

from http.client import HTTPMessage
from logging import Logger
from typing import Any, Iterable, Mapping
from urllib.parse import SplitResult

from botocore.awsrequest import AWSRequest
from botocore.compat import HAS_CRT as HAS_CRT
from botocore.compat import MD5_AVAILABLE as MD5_AVAILABLE
from botocore.credentials import Credentials, ReadOnlyCredentials
from botocore.crt.auth import CRT_AUTH_TYPE_MAPS as CRT_AUTH_TYPE_MAPS
from botocore.utils import IdentityCache

logger: Logger = ...

EMPTY_SHA256_HASH: str
PAYLOAD_BUFFER: int
ISO8601: str
SIGV4_TIMESTAMP: str
SIGNED_HEADERS_BLACKLIST: list[str]
UNSIGNED_PAYLOAD: str
STREAMING_UNSIGNED_PAYLOAD_TRAILER: str

class BaseSigner:
    REQUIRES_REGION: bool = ...
    REQUIRES_TOKEN: bool = ...
    def add_auth(self, request: AWSRequest) -> AWSRequest | None: ...

class TokenSigner(BaseSigner):
    def __init__(self, auth_token: str) -> None: ...

class SigV2Auth(BaseSigner):
    def __init__(self, credentials: Credentials | ReadOnlyCredentials) -> None:
        self.credentials: Credentials | ReadOnlyCredentials

    def calc_signature(self, request: AWSRequest, params: Mapping[str, Any]) -> tuple[str, str]: ...
    def add_auth(self, request: AWSRequest) -> AWSRequest: ...

class SigV3Auth(BaseSigner):
    def __init__(self, credentials: Credentials | ReadOnlyCredentials) -> None:
        self.credentials: Credentials | ReadOnlyCredentials

    def add_auth(self, request: AWSRequest) -> None: ...

class SigV4Auth(BaseSigner):
    REQUIRES_REGION: bool = ...
    def __init__(
        self, credentials: Credentials | ReadOnlyCredentials, service_name: str, region_name: str
    ) -> None:
        self.credentials: Credentials | ReadOnlyCredentials

    def headers_to_sign(self, request: AWSRequest) -> HTTPMessage: ...
    def canonical_query_string(self, request: AWSRequest) -> str: ...
    def canonical_headers(self, headers_to_sign: Iterable[str]) -> str: ...
    def signed_headers(self, headers_to_sign: Iterable[str]) -> str: ...
    def payload(self, request: AWSRequest) -> str: ...
    def canonical_request(self, request: AWSRequest) -> str: ...
    def scope(self, request: AWSRequest) -> str: ...
    def credential_scope(self, request: AWSRequest) -> str: ...
    def string_to_sign(self, request: AWSRequest, canonical_request: str) -> str: ...
    def signature(self, string_to_sign: str, request: AWSRequest) -> str: ...
    def add_auth(self, request: AWSRequest) -> None: ...

class S3SigV4Auth(SigV4Auth): ...

class S3ExpressAuth(S3SigV4Auth):
    REQUIRES_IDENTITY_CACHE: bool = ...

    def __init__(
        self,
        credentials: Credentials | ReadOnlyCredentials,
        service_name: str,
        region_name: str,
        *,
        identity_cache: IdentityCache,
    ) -> None: ...

class S3ExpressPostAuth(S3ExpressAuth): ...

class S3ExpressQueryAuth(S3ExpressAuth):
    DEFAULT_EXPIRES: int = ...

    def __init__(
        self,
        credentials: Credentials | ReadOnlyCredentials,
        service_name: str,
        region_name: str,
        *,
        identity_cache: IdentityCache,
        expires: int = ...,
    ) -> None: ...

class SigV4QueryAuth(SigV4Auth):
    DEFAULT_EXPIRES: int = ...
    def __init__(
        self,
        credentials: Credentials | ReadOnlyCredentials,
        service_name: str,
        region_name: str,
        expires: int = ...,
    ) -> None: ...

class S3SigV4QueryAuth(SigV4QueryAuth):
    def payload(self, request: AWSRequest) -> str: ...

class S3SigV4PostAuth(SigV4Auth):
    def add_auth(self, request: AWSRequest) -> None: ...

class HmacV1Auth(BaseSigner):
    QSAOfInterest: list[str] = ...
    def __init__(
        self,
        credentials: Credentials | ReadOnlyCredentials,
        service_name: str | None = ...,
        region_name: str | None = ...,
    ) -> None:
        self.credentials: Credentials | ReadOnlyCredentials

    def sign_string(self, string_to_sign: str) -> str: ...
    def canonical_standard_headers(self, headers: Mapping[str, Any]) -> str: ...
    def canonical_custom_headers(self, headers: Mapping[str, Any]) -> str: ...
    def unquote_v(self, nv: str) -> tuple[str, str] | str: ...
    def canonical_resource(self, split: SplitResult, auth_path: str | None = ...) -> str: ...
    def canonical_string(
        self,
        method: str,
        split: SplitResult,
        headers: Mapping[str, Any],
        expires: int | None = ...,
        auth_path: str | None = ...,
    ) -> Any: ...
    def get_signature(
        self,
        method: str,
        split: SplitResult,
        headers: Mapping[str, Any],
        expires: int | None = ...,
        auth_path: str | None = ...,
    ) -> Any: ...
    def add_auth(self, request: AWSRequest) -> None: ...

class HmacV1QueryAuth(HmacV1Auth):
    DEFAULT_EXPIRES: int = ...
    def __init__(self, credentials: Credentials | ReadOnlyCredentials, expires: int = ...) -> None:
        self.credentials: Credentials | ReadOnlyCredentials

class HmacV1PostAuth(HmacV1Auth):
    def add_auth(self, request: AWSRequest) -> None: ...

class BearerAuth(TokenSigner):
    def add_auth(self, request: AWSRequest) -> None: ...

def resolve_auth_type(auth_trait: Iterable[str]) -> str: ...

AUTH_TYPE_MAPS: dict[str, type[BaseSigner]]
AUTH_TYPE_TO_SIGNATURE_VERSION: dict[str, str]
