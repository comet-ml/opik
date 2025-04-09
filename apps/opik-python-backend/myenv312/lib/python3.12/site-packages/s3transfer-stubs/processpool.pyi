"""
Type annotations for s3transfer.processpool module.

Copyright 2025 Vlad Emelianov
"""

import logging
import multiprocessing
from queue import Queue
from typing import Any, Callable, Generator, Mapping, NamedTuple, TypeVar

from botocore.client import BaseClient
from s3transfer.compat import MAXINT as MAXINT
from s3transfer.compat import BaseManager as BaseManager
from s3transfer.constants import ALLOWED_DOWNLOAD_ARGS as ALLOWED_DOWNLOAD_ARGS
from s3transfer.constants import MB as MB
from s3transfer.constants import PROCESS_USER_AGENT as PROCESS_USER_AGENT
from s3transfer.exceptions import CancelledError as CancelledError
from s3transfer.exceptions import RetriesExceededError as RetriesExceededError
from s3transfer.futures import BaseTransferFuture as BaseTransferFuture
from s3transfer.futures import BaseTransferMeta as BaseTransferMeta
from s3transfer.futures import TransferFuture
from s3transfer.manager import TransferConfig
from s3transfer.utils import S3_RETRYABLE_DOWNLOAD_ERRORS as S3_RETRYABLE_DOWNLOAD_ERRORS
from s3transfer.utils import CallArgs as CallArgs
from s3transfer.utils import OSUtils as OSUtils
from s3transfer.utils import calculate_num_parts as calculate_num_parts
from s3transfer.utils import calculate_range_parameter as calculate_range_parameter

_R = TypeVar("_R")

logger: logging.Logger
SHUTDOWN_SIGNAL: str

class DownloadFileRequest(NamedTuple):
    transfer_id: str
    bucket: str
    key: str
    filename: str
    extra_args: dict[str, Any]
    expected_size: int

class GetObjectJob(NamedTuple):
    transfer_id: str
    bucket: str
    key: str
    temp_filename: str
    extra_args: dict[str, Any]
    offset: int
    filename: str

def ignore_ctrl_c() -> Generator[None, None, None]: ...

class ProcessTransferConfig:
    multipart_threshold: int
    multipart_chunksize: int
    max_request_processes: int
    def __init__(
        self,
        multipart_threshold: int = ...,
        multipart_chunksize: int = ...,
        max_request_processes: int = ...,
    ) -> None: ...

class ProcessPoolDownloader:
    def __init__(
        self,
        client_kwargs: Mapping[str, Any] | None = ...,
        config: ProcessTransferConfig | None = ...,
    ) -> None: ...
    def download_file(
        self,
        bucket: str,
        key: str,
        filename: str,
        extra_args: Mapping[str, Any] | None = ...,
        expected_size: int | None = ...,
    ) -> TransferFuture: ...
    def shutdown(self) -> None: ...
    def __enter__(self: _R) -> _R: ...
    def __exit__(
        self,
        exc_type: type[BaseException] | None,
        exc_value: BaseException | None,
        *args: object,
    ) -> None: ...

class ProcessPoolTransferFuture(BaseTransferFuture):
    def __init__(self, monitor: TransferMonitor, meta: ProcessPoolTransferMeta) -> None: ...
    @property
    def meta(self) -> ProcessPoolTransferMeta: ...
    def done(self) -> bool: ...
    def result(self) -> str: ...
    def cancel(self) -> None: ...

class ProcessPoolTransferMeta(BaseTransferMeta):
    def __init__(self, transfer_id: str, call_args: Mapping[str, Any]) -> None: ...
    @property
    def call_args(self) -> dict[str, Any]: ...
    @property
    def transfer_id(self) -> str: ...
    @property
    def user_context(self) -> dict[str, Any]: ...

class ClientFactory:
    def __init__(self, client_kwargs: Mapping[str, Any] | None = ...) -> None: ...
    def create_client(self) -> BaseClient: ...

class TransferMonitor:
    def __init__(self) -> None: ...
    def notify_new_transfer(self) -> str: ...
    def is_done(self, transfer_id: str) -> bool: ...
    def notify_done(self, transfer_id: str) -> None: ...
    def poll_for_result(self, transfer_id: str) -> None: ...
    def notify_exception(self, transfer_id: str, exception: BaseException) -> None: ...
    def notify_cancel_all_in_progress(self) -> None: ...
    def get_exception(self, transfer_id: str) -> BaseException | None: ...
    def notify_expected_jobs_to_complete(self, transfer_id: str, num_jobs: int) -> None: ...
    def notify_job_complete(self, transfer_id: str) -> int: ...

class TransferState:
    def __init__(self) -> None: ...
    @property
    def done(self) -> bool: ...
    def set_done(self) -> None: ...
    def wait_till_done(self) -> None: ...
    @property
    def exception(self) -> BaseException | None: ...
    @exception.setter
    def exception(self, val: BaseException) -> None: ...
    @property
    def jobs_to_complete(self) -> int: ...
    @jobs_to_complete.setter
    def jobs_to_complete(self, val: int) -> None: ...
    def decrement_jobs_to_complete(self) -> int: ...

class TransferMonitorManager(BaseManager):
    TransferMonitor: Callable[..., TransferMonitor]

class BaseS3TransferProcess(multiprocessing.Process):
    def __init__(self, client_factory: ClientFactory) -> None: ...
    def run(self) -> None: ...

class GetObjectSubmitter(BaseS3TransferProcess):
    def __init__(
        self,
        transfer_config: TransferConfig,
        client_factory: ClientFactory,
        transfer_monitor: TransferMonitor,
        osutil: OSUtils,
        download_request_queue: Queue[Any],
        worker_queue: Queue[Any],
    ) -> None: ...

class GetObjectWorker(BaseS3TransferProcess):
    def __init__(
        self,
        queue: Queue[Any],
        client_factory: ClientFactory,
        transfer_monitor: TransferMonitor,
        osutil: OSUtils,
    ) -> None: ...
