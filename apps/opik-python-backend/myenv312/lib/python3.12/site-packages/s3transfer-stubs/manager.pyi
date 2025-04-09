"""
Type annotations for s3transfer.manager module.

Copyright 2025 Vlad Emelianov
"""

import logging
from typing import IO, Any, Mapping, Sequence, TypeVar

from botocore.client import BaseClient
from s3transfer.bandwidth import BandwidthLimiter as BandwidthLimiter
from s3transfer.bandwidth import LeakyBucket as LeakyBucket
from s3transfer.constants import ALLOWED_DOWNLOAD_ARGS as ALLOWED_DOWNLOAD_ARGS
from s3transfer.constants import KB as KB
from s3transfer.constants import MB as MB
from s3transfer.copies import CopySubmissionTask as CopySubmissionTask
from s3transfer.delete import DeleteSubmissionTask as DeleteSubmissionTask
from s3transfer.download import DownloadSubmissionTask as DownloadSubmissionTask
from s3transfer.exceptions import CancelledError as CancelledError
from s3transfer.exceptions import FatalError as FatalError
from s3transfer.futures import IN_MEMORY_DOWNLOAD_TAG as IN_MEMORY_DOWNLOAD_TAG
from s3transfer.futures import IN_MEMORY_UPLOAD_TAG as IN_MEMORY_UPLOAD_TAG
from s3transfer.futures import BaseExecutor
from s3transfer.futures import BoundedExecutor as BoundedExecutor
from s3transfer.futures import TransferCoordinator as TransferCoordinator
from s3transfer.futures import TransferFuture as TransferFuture
from s3transfer.futures import TransferMeta as TransferMeta
from s3transfer.subscribers import BaseSubscriber
from s3transfer.upload import UploadSubmissionTask as UploadSubmissionTask
from s3transfer.utils import CallArgs as CallArgs
from s3transfer.utils import OSUtils as OSUtils
from s3transfer.utils import SlidingWindowSemaphore as SlidingWindowSemaphore
from s3transfer.utils import TaskSemaphore as TaskSemaphore
from s3transfer.utils import get_callbacks as get_callbacks
from s3transfer.utils import signal_not_transferring as signal_not_transferring
from s3transfer.utils import signal_transferring as signal_transferring

_R = TypeVar("_R")

logger: logging.Logger

class TransferConfig:
    multipart_threshold: int
    multipart_chunksize: int
    max_request_concurrency: int
    max_submission_concurrency: int
    max_request_queue_size: int
    max_submission_queue_size: int
    max_io_queue_size: int
    io_chunksize: int
    num_download_attempts: int
    max_in_memory_upload_chunks: int
    max_in_memory_download_chunks: int
    max_bandwidth: int
    def __init__(
        self,
        multipart_threshold: int = ...,
        multipart_chunksize: int = ...,
        max_request_concurrency: int = ...,
        max_submission_concurrency: int = ...,
        max_request_queue_size: int = ...,
        max_submission_queue_size: int = ...,
        max_io_queue_size: int = ...,
        io_chunksize: int = ...,
        num_download_attempts: int = ...,
        max_in_memory_upload_chunks: int = ...,
        max_in_memory_download_chunks: int = ...,
        max_bandwidth: int | None = ...,
    ) -> None: ...

class TransferManager:
    ALLOWED_DOWNLOAD_ARGS: list[str]
    ALLOWED_UPLOAD_ARGS: list[str]
    ALLOWED_COPY_ARGS: list[str]
    ALLOWED_DELETE_ARGS: list[str]
    VALIDATE_SUPPORTED_BUCKET_VALUES: bool
    def __init__(
        self,
        client: BaseClient,
        config: TransferConfig | None = ...,
        osutil: OSUtils | None = ...,
        executor_cls: type[BaseExecutor] | None = ...,
    ) -> None: ...
    @property
    def client(self) -> BaseClient: ...
    @property
    def config(self) -> TransferConfig: ...
    def upload(
        self,
        fileobj: IO[Any] | str | bytes,
        bucket: str,
        key: str,
        extra_args: Mapping[str, Any] | None = ...,
        subscribers: Sequence[BaseSubscriber] | None = ...,
    ) -> TransferFuture: ...
    def download(
        self,
        bucket: str,
        key: str,
        fileobj: IO[Any] | str | bytes,
        extra_args: Mapping[str, Any] | None = ...,
        subscribers: Sequence[BaseSubscriber] | None = ...,
    ) -> TransferFuture: ...
    def copy(
        self,
        copy_source: Mapping[str, Any],
        bucket: str,
        key: str,
        extra_args: Mapping[str, Any] | None = ...,
        subscribers: Sequence[BaseSubscriber] | None = ...,
        source_client: BaseClient | None = ...,
    ) -> TransferFuture: ...
    def delete(
        self,
        bucket: str,
        key: str,
        extra_args: Mapping[str, Any] | None = ...,
        subscribers: Sequence[BaseSubscriber] | None = ...,
    ) -> TransferFuture: ...
    def __enter__(self: _R) -> _R: ...
    def __exit__(
        self,
        exc_type: type[BaseException] | None,
        exc_value: BaseException | None,
        *args: object,
    ) -> None: ...
    def shutdown(self, cancel: bool = ..., cancel_msg: str = ...) -> None: ...

class TransferCoordinatorController:
    def __init__(self) -> None: ...
    @property
    def tracked_transfer_coordinators(self) -> set[TransferCoordinator]: ...
    def add_transfer_coordinator(self, transfer_coordinator: TransferCoordinator) -> None: ...
    def remove_transfer_coordinator(self, transfer_coordinator: TransferCoordinator) -> None: ...
    def cancel(self, msg: str = ..., exc_type: type[BaseException] = ...) -> None: ...
    def wait(self) -> None: ...
