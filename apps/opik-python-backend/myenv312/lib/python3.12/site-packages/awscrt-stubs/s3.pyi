"""
Type annotations for awscrt.s3 module.

Copyright 2024 Vlad Emelianov
"""

from concurrent.futures import Future
from dataclasses import dataclass
from enum import IntEnum
from threading import Event
from types import TracebackType
from typing import Any, Callable, Sequence

from awscrt import NativeResource as NativeResource
from awscrt.auth import AwsCredentialsProvider as AwsCredentialsProvider
from awscrt.auth import AwsSigningConfig
from awscrt.exceptions import AwsCrtError
from awscrt.http import HttpRequest as HttpRequest
from awscrt.io import ClientBootstrap as ClientBootstrap
from awscrt.io import TlsConnectionOptions

class CrossProcessLock(NativeResource):
    def __init__(self, lock_scope_name: str) -> None: ...
    def acquire(self) -> None: ...
    def __enter__(self) -> None: ...
    def release(self) -> None: ...
    def __exit__(
        self,
        exc_type: type[BaseException] | None,
        exc_value: BaseException | None,
        exc_tb: TracebackType | None,
    ) -> None: ...

class S3RequestType(IntEnum):
    DEFAULT = 0
    GET_OBJECT = 1
    PUT_OBJECT = 2

class S3RequestTlsMode(IntEnum):
    ENABLED = 0
    DISABLED = 1

class S3ChecksumAlgorithm(IntEnum):
    CRC32C = 1
    CRC32 = 2
    SHA1 = 3
    SHA256 = 4
    CRC64NVME = 5

class S3ChecksumLocation(IntEnum):
    HEADER = 1
    TRAILER = 2

@dataclass
class S3ChecksumConfig:
    algorithm: S3ChecksumAlgorithm | None = ...
    location: S3ChecksumLocation | None = ...
    validate_response: bool = ...

class S3Client(NativeResource):
    shutdown_event: Event
    def __init__(
        self,
        *,
        bootstrap: ClientBootstrap | None = ...,
        region: str,
        tls_mode: S3RequestTlsMode | None = ...,
        signing_config: AwsSigningConfig | None = ...,
        credential_provider: S3RequestTlsMode | None = ...,
        tls_connection_options: TlsConnectionOptions | None = ...,
        part_size: int | None = ...,
        multipart_upload_threshold: int | None = ...,
        throughput_target_gbps: float | None = ...,
        enable_s3express: bool = ...,
        memory_limit: int | None = ...,
        network_interface_names: Sequence[str] | None = ...,
    ) -> None: ...
    def make_request(
        self,
        *,
        type: S3RequestType,
        request: HttpRequest,
        operation_name: str | None = ...,
        signing_config: AwsSigningConfig | None = ...,
        credential_provider: AwsCredentialsProvider | None = ...,
        checksum_config: S3ChecksumConfig | None = ...,
        part_size: int | None = ...,
        multipart_upload_threshold: int | None = ...,
        recv_filepath: str | None = ...,
        send_filepath: str | None = ...,
        on_headers: Callable[[int, list[tuple[str, str]]], None] | None = ...,
        on_body: Callable[[bytes, int], None] | None = ...,
        on_done: Callable[[BaseException | None, list[tuple[str, str]] | None, bytes | None], None]
        | None = ...,
        on_progress: Callable[[int], None] | None = ...,
    ) -> S3Request: ...

class S3Request(NativeResource):
    shutdown_event: Event
    def __init__(
        self,
        *,
        client: S3Client,
        type: S3RequestType,
        request: HttpRequest,
        operation_name: str | None = ...,
        signing_config: AwsSigningConfig | None = ...,
        credential_provider: AwsCredentialsProvider | None = ...,
        checksum_config: S3ChecksumConfig | None = ...,
        part_size: int | None = ...,
        multipart_upload_threshold: int | None = ...,
        recv_filepath: str | None = ...,
        send_filepath: str | None = ...,
        on_headers: Callable[[int, list[tuple[str, str]]], None] | None = ...,
        on_body: Callable[[bytes, int], None] | None = ...,
        on_done: Callable[[BaseException | None, list[tuple[str, str]] | None, bytes | None], None]
        | None = ...,
        on_progress: Callable[[int], None] | None = ...,
        region: str | None = ...,
    ) -> None: ...
    @property
    def finished_future(self) -> Future[BaseException | None]: ...
    def cancel(self) -> None: ...

class S3ResponseError(AwsCrtError):
    def __init__(
        self,
        *,
        code: int,
        name: str,
        message: str,
        status_code: list[tuple[str, str]] | None = ...,
        headers: list[tuple[str, str]] | None = ...,
        body: bytes | None = ...,
        operation_name: str | None = ...,
    ) -> None: ...

class _S3ClientCore:
    def __init__(
        self,
        bootstrap: ClientBootstrap,
        credential_provider: AwsCredentialsProvider | None = ...,
        signing_config: AwsSigningConfig | None = ...,
        tls_connection_options: TlsConnectionOptions | None = ...,
    ) -> None: ...

class _S3RequestCore:
    def __init__(
        self,
        request: HttpRequest,
        finish_future: Future[BaseException | None],
        shutdown_event: Event,
        signing_config: AwsSigningConfig | None = ...,
        credential_provider: AwsCredentialsProvider | None = ...,
        on_headers: Callable[[int, list[tuple[str, str]]], None] | None = ...,
        on_body: Callable[[bytes, int], None] | None = ...,
        on_done: Callable[[BaseException | None, list[tuple[str, str]] | None, bytes | None], None]
        | None = ...,
        on_progress: Callable[[int], None] | None = ...,
    ) -> None: ...

def create_default_s3_signing_config(
    *, region: str, credential_provider: AwsCredentialsProvider, **kwargs: Any
) -> AwsSigningConfig: ...
def get_ec2_instance_type() -> str: ...
def is_optimized_for_system() -> bool: ...
def get_optimized_platforms() -> list[str]: ...
def get_recommended_throughput_target_gbps() -> float | None: ...
