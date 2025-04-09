"""
Type annotations for s3transfer.crt module.

Copyright 2025 Vlad Emelianov
"""

import logging
from io import BytesIO
from typing import (
    IO,
    Any,
    Callable,
    Iterator,
    Mapping,
    Sequence,
    TypeVar,
)

from awscrt.auth import AwsCredentials, AwsCredentialsProvider
from awscrt.http import HttpRequest
from awscrt.s3 import CrossProcessLock, S3Client, S3Request
from botocore.session import Session
from s3transfer.constants import MB as MB
from s3transfer.exceptions import TransferNotDoneError as TransferNotDoneError
from s3transfer.futures import BaseTransferFuture as BaseTransferFuture
from s3transfer.futures import BaseTransferMeta as BaseTransferMeta
from s3transfer.subscribers import BaseSubscriber
from s3transfer.utils import CallArgs as CallArgs
from s3transfer.utils import OSUtils as OSUtils
from s3transfer.utils import get_callbacks as get_callbacks

_R = TypeVar("_R")

logger: logging.Logger = ...
CRT_S3_PROCESS_LOCK: CrossProcessLock | None = ...

def acquire_crt_s3_process_lock(name: str) -> CrossProcessLock: ...
def create_s3_crt_client(
    region: str,
    crt_credentials_provider: AwsCredentialsProvider | None = ...,
    num_threads: int | None = ...,
    target_throughput: float | None = ...,
    part_size: int = ...,
    use_ssl: bool = ...,
    verify: bool | str | None = ...,
) -> S3Client: ...

class CRTTransferManager:
    ALLOWED_DOWNLOAD_ARGS: list[str] = ...
    ALLOWED_UPLOAD_ARGS: list[str] = ...
    ALLOWED_DELETE_ARGS: list[str] = ...
    VALIDATE_SUPPORTED_BUCKET_VALUES: bool = ...

    def __init__(
        self,
        crt_s3_client: S3Client,  # type: ignore
        crt_request_serializer: BaseCRTRequestSerializer,
        osutil: OSUtils | None = ...,
    ) -> None: ...
    def __enter__(self: _R) -> _R: ...
    def __exit__(
        self,
        exc_type: type[BaseException] | None,
        exc_value: BaseException | None,
        *args: object,
    ) -> None: ...
    def download(
        self,
        bucket: str,
        key: str,
        fileobj: IO[Any] | str | bytes,
        extra_args: Mapping[str, Any] | None = ...,
        subscribers: Sequence[BaseSubscriber] | None = ...,
    ) -> CRTTransferFuture: ...
    def upload(
        self,
        fileobj: IO[Any] | str | bytes,
        bucket: str,
        key: str,
        extra_args: Mapping[str, Any] | None = ...,
        subscribers: Sequence[BaseSubscriber] | None = ...,
    ) -> CRTTransferFuture: ...
    def delete(
        self,
        bucket: str,
        key: str,
        extra_args: Mapping[str, Any] | None = ...,
        subscribers: Sequence[BaseSubscriber] | None = ...,
    ) -> CRTTransferFuture: ...
    def shutdown(self, cancel: bool = ...) -> None: ...

class CRTTransferMeta(BaseTransferMeta):
    def __init__(
        self, transfer_id: str | None = ..., call_args: Mapping[str, Any] | None = ...
    ) -> None: ...
    @property
    def call_args(self) -> dict[str, Any]: ...
    @property
    def transfer_id(self) -> str: ...
    @property
    def user_context(self) -> dict[str, Any]: ...

class CRTTransferFuture(BaseTransferFuture):
    def __init__(
        self,
        meta: CRTTransferMeta | None = ...,
        coordinator: CRTTransferCoordinator | None = ...,
    ) -> None: ...
    @property
    def meta(self) -> CRTTransferMeta: ...
    def done(self) -> bool: ...
    # FIXME: Signature of "result" incompatible with supertype "BaseTransferFuture"
    def result(self, timeout: float | None = ...) -> None: ...  # type: ignore
    def cancel(self) -> None: ...
    def set_exception(self, exception: BaseException) -> None: ...

class BaseCRTRequestSerializer:
    def serialize_http_request(
        self, transfer_type: str, future: CRTTransferFuture
    ) -> HttpRequest: ...
    def translate_crt_exception(self, exception: Exception) -> Exception | None: ...

class BotocoreCRTRequestSerializer(BaseCRTRequestSerializer):
    def __init__(self, session: Session, client_kwargs: Mapping[str, Any] | None = ...) -> None: ...
    def serialize_http_request(
        self, transfer_type: str, future: CRTTransferFuture
    ) -> HttpRequest: ...

class FakeRawResponse(BytesIO):
    def stream(self, amt: int = ..., decode_content: bool | None = ...) -> Iterator[bytes]: ...

class BotocoreCRTCredentialsWrapper:
    def __init__(self, resolved_botocore_credentials: AwsCredentials) -> None: ...
    def __call__(self) -> AwsCredentials: ...
    def to_crt_credentials_provider(self) -> AwsCredentialsProvider: ...

class CRTTransferCoordinator:
    def __init__(
        self,
        transfer_id: str | None = ...,
        s3_request: S3Request | None = ...,
        exception_translator: Callable[[Exception], Exception | None] | None = ...,
    ) -> None:
        self.transfer_id: str

    @property
    def s3_request(self) -> S3Request: ...
    def set_done_callbacks_complete(self) -> None: ...
    def wait_until_on_done_callbacks_complete(self, timeout: float | None = ...) -> None: ...
    def set_exception(self, exception: BaseException, override: bool = ...) -> None: ...
    def cancel(self) -> None: ...
    def result(self, timeout: float | None = ...) -> None: ...
    def handle_exception(self, exc: Exception) -> None: ...
    def done(self) -> bool: ...
    def set_s3_request(
        self,
        s3_request: S3Request,  # type: ignore
    ) -> None: ...

class S3ClientArgsCreator:
    def __init__(
        self, crt_request_serializer: BaseCRTRequestSerializer, os_utils: OSUtils
    ) -> None: ...
    def get_make_request_args(
        self,
        request_type: str,
        call_args: Mapping[str, Any],
        coordinator: CRTTransferCoordinator,
        future: CRTTransferFuture,
        on_done_after_calls: Sequence[Callable[..., Any]],
    ) -> dict[str, Any]: ...
    def get_crt_callback(
        self,
        future: CRTTransferFuture,
        callback_type: str,
        before_subscribers: Sequence[BaseSubscriber] | None = ...,
        after_subscribers: Sequence[BaseSubscriber] | None = ...,
    ) -> Callable[..., Any]: ...

class RenameTempFileHandler:
    def __init__(
        self,
        coordinator: CRTTransferCoordinator,
        final_filename: str,
        temp_filename: str,
        osutil: OSUtils,
    ) -> None: ...
    def __call__(self, **kwargs: Any) -> None: ...

class AfterDoneHandler:
    def __init__(self, coordinator: CRTTransferCoordinator) -> None: ...
    def __call__(self, **kwargs: Any) -> None: ...

class OnBodyFileObjWriter:
    def __init__(self, fileobj: IO[bytes]) -> None: ...
    def __call__(self, chunk: bytes, **kwargs: Any) -> None: ...
