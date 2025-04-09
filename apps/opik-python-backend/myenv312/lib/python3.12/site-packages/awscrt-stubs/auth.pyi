"""
Type annotations for awscrt.auth module.

Copyright 2024 Vlad Emelianov
"""

from concurrent.futures import Future
from datetime import datetime
from enum import IntEnum
from typing import Any, Callable, Sequence, TypeVar

from awscrt import NativeResource as NativeResource
from awscrt.http import HttpProxyOptions
from awscrt.http import HttpRequest as HttpRequest
from awscrt.io import ClientBootstrap as ClientBootstrap
from awscrt.io import ClientTlsContext

_R = TypeVar("_R")

class AwsCredentials(NativeResource):
    def __init__(
        self,
        access_key_id: str,
        secret_access_key: str,
        session_token: str | None = ...,
        expiration: datetime | None = ...,
    ) -> None: ...
    @property
    def access_key_id(self) -> str: ...
    @property
    def secret_access_key(self) -> str: ...
    @property
    def session_token(self) -> str: ...
    @property
    def expiration(self) -> datetime | None: ...
    def __deepcopy__(self: _R, memo: Any) -> _R: ...

class AwsCredentialsProviderBase(NativeResource): ...

class AwsCredentialsProvider(AwsCredentialsProviderBase):
    def __init__(self, binding: Any) -> None: ...
    @classmethod
    def new_default_chain(cls: type[_R], client_bootstrap: ClientBootstrap | None = ...) -> _R: ...
    @classmethod
    def new_static(
        cls: type[_R],
        access_key_id: str,
        secret_access_key: str,
        session_token: str | None = ...,
    ) -> _R: ...
    @classmethod
    def new_profile(
        cls: type[_R],
        client_bootstrap: ClientBootstrap | None = ...,
        profile_name: str | None = ...,
        config_filepath: str | None = ...,
        credentials_filepath: str | None = ...,
    ) -> _R: ...
    @classmethod
    def new_process(cls: type[_R], profile_to_use: str | None = ...) -> _R: ...
    @classmethod
    def new_environment(cls: type[_R]) -> _R: ...
    @classmethod
    def new_chain(cls: type[_R], providers: list[AwsCredentialsProvider]) -> _R: ...
    @classmethod
    def new_delegate(cls: type[_R], get_credentials: Callable[[], AwsCredentials]) -> _R: ...
    @classmethod
    def new_cognito(
        cls: type[_R],
        *,
        endpoint: str,
        identity: str,
        tls_ctx: ClientTlsContext,
        logins: Sequence[tuple[str, str]] | None = ...,
        custom_role_arn: str | None = ...,
        client_bootstrap: ClientBootstrap | None = ...,
        http_proxy_options: HttpProxyOptions | None = ...,
    ) -> _R: ...
    @classmethod
    def new_x509(
        cls: type[_R],
        *,
        endpoint: str,
        thing_name: str,
        role_alias: str,
        tls_ctx: ClientTlsContext,
        client_bootstrap: ClientBootstrap | None = ...,
        http_proxy_options: HttpProxyOptions | None = ...,
    ) -> _R: ...
    def get_credentials(self) -> Future[AwsCredentials]: ...

class AwsSigningAlgorithm(IntEnum):
    V4 = 0
    V4_ASYMMETRIC = 1
    V4_S3EXPRESS = 2

class AwsSignatureType(IntEnum):
    HTTP_REQUEST_HEADERS = 0
    HTTP_REQUEST_QUERY_PARAMS = 1

class AwsSignedBodyValue:
    EMPTY_SHA256: str
    UNSIGNED_PAYLOAD: str
    STREAMING_AWS4_HMAC_SHA256_PAYLOAD: str
    STREAMING_AWS4_HMAC_SHA256_EVENTS: str

class AwsSignedBodyHeaderType(IntEnum):
    NONE = 0
    X_AMZ_CONTENT_SHA_256 = 1

class AwsSigningConfig(NativeResource):
    def __init__(
        self,
        algorithm: AwsSigningAlgorithm = ...,
        signature_type: AwsSignatureType = ...,
        credentials_provider: AwsCredentialsProvider | None = ...,
        region: str = ...,
        service: str = ...,
        date: datetime | None = ...,
        should_sign_header: Callable[[str], bool] | None = ...,
        use_double_uri_encode: bool = ...,
        should_normalize_uri_path: bool = ...,
        signed_body_value: str | None = ...,
        signed_body_header_type: AwsSignedBodyHeaderType = ...,
        expiration_in_seconds: int | None = ...,
        omit_session_token: bool = ...,
    ) -> None: ...
    def replace(self: _R, **kwargs: Any) -> _R: ...
    @property
    def algorithm(self) -> AwsSigningAlgorithm: ...
    @property
    def signature_type(self) -> AwsSignatureType: ...
    @property
    def credentials_provider(self) -> AwsCredentialsProvider: ...
    @property
    def region(self) -> str: ...
    @property
    def service(self) -> str: ...
    @property
    def date(self) -> datetime: ...
    @property
    def should_sign_header(self) -> Callable[[str], bool] | None: ...
    @property
    def use_double_uri_encode(self) -> bool: ...
    @property
    def should_normalize_uri_path(self) -> bool: ...
    @property
    def signed_body_value(self) -> str | None: ...
    @property
    def signed_body_header_type(self) -> AwsSignedBodyHeaderType: ...
    @property
    def expiration_in_seconds(self) -> int | None: ...
    @property
    def omit_session_token(self) -> bool: ...

def aws_sign_request(
    http_request: HttpRequest, signing_config: AwsSigningConfig
) -> Future[HttpRequest]: ...
