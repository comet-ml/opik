"""
Type annotations for boto3.s3.transfer module.

Copyright 2024 Vlad Emelianov
"""

import logging
from types import TracebackType
from typing import Any, Callable, Mapping, TypeVar

from botocore.client import BaseClient
from botocore.config import Config
from s3transfer.manager import TransferConfig as S3TransferConfig
from s3transfer.manager import TransferManager
from s3transfer.subscribers import BaseSubscriber
from s3transfer.utils import OSUtils

_R = TypeVar("_R")

KB: int
MB: int

logger: logging.Logger = ...

def create_transfer_manager(
    client: BaseClient,
    config: TransferConfig,
    osutil: OSUtils | None = ...,
) -> TransferManager: ...
def has_minimum_crt_version(minimum_version: str) -> bool: ...

class TransferConfig(S3TransferConfig):
    ALIAS: dict[str, str]

    def __init__(
        self,
        multipart_threshold: int = ...,
        max_concurrency: int = ...,
        multipart_chunksize: int = ...,
        num_download_attempts: int = ...,
        max_io_queue: int = ...,
        io_chunksize: int = ...,
        use_threads: bool = ...,
        max_bandwidth: int | None = ...,
        preferred_transfer_client: str = ...,
    ) -> None:
        self.use_threads: bool

    def __setattr__(self, name: str, value: int) -> None: ...

class S3Transfer:
    ALLOWED_DOWNLOAD_ARGS: list[str]
    ALLOWED_UPLOAD_ARGS: list[str]
    def __init__(
        self,
        client: BaseClient | None = ...,
        config: Config | None = ...,
        osutil: OSUtils | None = ...,
        manager: TransferManager | None = ...,
    ) -> None: ...
    def upload_file(
        self,
        filename: str,
        bucket: str,
        key: str,
        callback: Callable[[int], Any] | None = ...,
        extra_args: Mapping[str, Any] | None = ...,
    ) -> None: ...
    def download_file(
        self,
        bucket: str,
        key: str,
        filename: str,
        extra_args: Mapping[str, Any] | None = ...,
        callback: Callable[[int], Any] | None = ...,
    ) -> None: ...
    def __enter__(self: _R) -> _R: ...
    def __exit__(
        self,
        exc_type: type[BaseException] | None,
        exc_value: BaseException | None,
        tb: TracebackType | None,
    ) -> None: ...

class ProgressCallbackInvoker(BaseSubscriber):
    def __init__(self, callback: Callable[[int], Any]) -> None: ...
    # FIXME: signature incompatible with BaseSubscriber
    def on_progress(  # type: ignore [override]
        self, bytes_transferred: int, **kwargs: Any
    ) -> None: ...
