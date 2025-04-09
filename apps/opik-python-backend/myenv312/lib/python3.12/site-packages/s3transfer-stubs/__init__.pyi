"""
Type annotations for s3transfer module.

Copyright 2025 Vlad Emelianov
"""

import logging
from queue import Queue
from typing import IO, Any, Callable, Iterator, Mapping, TypeVar

from botocore.awsrequest import AWSRequest
from botocore.client import BaseClient
from s3transfer.exceptions import RetriesExceededError as RetriesExceededError
from s3transfer.exceptions import S3UploadFailedError as S3UploadFailedError
from s3transfer.futures import BaseExecutor

_R = TypeVar("_R")

class NullHandler(logging.Handler):
    def emit(self, record: Any) -> None: ...

logger: logging.Logger = ...
MB: int = ...
SHUTDOWN_SENTINEL: object = ...

def random_file_extension(num_digits: int = ...) -> str: ...
def disable_upload_callbacks(request: AWSRequest, operation_name: str, **kwargs: Any) -> None: ...
def enable_upload_callbacks(request: AWSRequest, operation_name: str, **kwargs: Any) -> None: ...

class QueueShutdownError(Exception): ...

class ReadFileChunk:
    def __init__(
        self,
        fileobj: IO[Any] | str | bytes,
        start_byte: int,
        chunk_size: int,
        full_file_size: int,
        callback: Callable[..., Any] | None = ...,
        enable_callback: bool = ...,
    ) -> None: ...
    @classmethod
    def from_filename(
        cls: type[_R],
        filename: str,
        start_byte: int,
        chunk_size: int,
        callback: Callable[..., Any] | None = ...,
        enable_callback: bool = ...,
    ) -> _R: ...
    def read(self, amount: int | None = ...) -> str: ...
    def enable_callback(self) -> None: ...
    def disable_callback(self) -> None: ...
    def seek(self, where: int) -> None: ...
    def close(self) -> None: ...
    def tell(self) -> int: ...
    def __len__(self) -> int: ...
    def __enter__(self: _R) -> _R: ...
    def __exit__(self, *args: object, **kwargs: Any) -> None: ...
    def __iter__(self) -> Iterator[str]: ...

class StreamReaderProgress:
    def __init__(self, stream: Any, callback: Callable[..., Any] | None = ...) -> None: ...
    def read(self, *args: Any, **kwargs: Any) -> str: ...

class OSUtils:
    def get_file_size(self, filename: str) -> int: ...
    def open_file_chunk_reader(
        self, filename: str, start_byte: int, size: int, callback: Callable[..., Any]
    ) -> IO[Any]: ...
    def open(self, filename: str, mode: str) -> IO[Any]: ...
    def remove_file(self, filename: str) -> None: ...
    def rename_file(self, current_filename: str, new_filename: str) -> None: ...

class MultipartUploader:
    UPLOAD_PART_ARGS: list[str]
    def __init__(
        self,
        client: BaseClient,
        config: TransferConfig,
        osutil: OSUtils,
        executor_cls: type[BaseExecutor] = ...,
    ) -> None: ...
    def upload_file(
        self,
        filename: str,
        bucket: str,
        key: str,
        callback: Callable[..., Any],
        extra_args: Mapping[str, Any],
    ) -> None: ...

class ShutdownQueue(Queue[Any]):
    def trigger_shutdown(self) -> None: ...
    # FIXME: Signature of "put" incompatible with supertype "Queue
    def put(self, item: Any) -> None: ...  # type: ignore

class MultipartDownloader:
    def __init__(
        self,
        client: BaseClient,
        config: TransferConfig,
        osutil: OSUtils,
        executor_cls: type[BaseExecutor] = ...,
    ) -> None: ...
    def download_file(
        self,
        bucket: str,
        key: str,
        filename: str,
        object_size: int,
        extra_args: dict[str, Any],
        callback: Callable[..., Any] | None = ...,
    ) -> None: ...

class TransferConfig:
    multipart_threshold: int
    max_concurrency: int
    multipart_chunksize: int
    num_download_attempts: int
    max_io_queue: int
    def __init__(
        self,
        multipart_threshold: int = ...,
        max_concurrency: int = ...,
        multipart_chunksize: int = ...,
        num_download_attempts: int = ...,
        max_io_queue: int = ...,
    ) -> None: ...

class S3Transfer:
    ALLOWED_DOWNLOAD_ARGS: list[str]
    ALLOWED_UPLOAD_ARGS: list[str]
    def __init__(
        self,
        client: BaseClient,
        config: TransferConfig | None = ...,
        osutil: OSUtils | None = ...,
    ) -> None: ...
    def upload_file(
        self,
        filename: str,
        bucket: str,
        key: str,
        callback: Callable[..., Any] | None = ...,
        extra_args: dict[str, Any] | None = ...,
    ) -> None: ...
    def download_file(
        self,
        bucket: str,
        key: str,
        filename: str,
        extra_args: dict[str, Any] | None = ...,
        callback: Callable[..., Any] | None = ...,
    ) -> None: ...
