import abc
import dataclasses
import logging
import math
import time
from concurrent.futures import Future, CancelledError
from typing import Callable, List, Optional

import httpx

from . import upload_options, upload_monitor, thread_pool, file_uploader
from ..message_processing import messages
from ..rest_api import client as rest_api_client
from .. import format_helpers


LOGGER = logging.getLogger(__name__)


@dataclasses.dataclass
class RemainingUploadData:
    uploads: int
    bytes: int
    total_size: int


@dataclasses.dataclass
class UploadResult:
    future: Future
    monitor: upload_monitor.FileUploadMonitor

    def ready(self) -> bool:
        """Allows to check if wrapped Future successfully finished"""
        return self.future.done()

    def successful(self, timeout: Optional[float] = None) -> bool:
        """Allows to check if wrapped Future completed without raising an exception"""
        try:
            return self.future.exception(timeout) is None
        except (CancelledError, TimeoutError):
            return False


def message_supports_upload(message: messages.BaseMessage) -> bool:
    return isinstance(message, messages.CreateAttachmentMessage)


class BaseFileUploadManager(abc.ABC):
    @abc.abstractmethod
    def upload(self, message: messages.BaseMessage) -> None:
        pass

    @abc.abstractmethod
    def remaining_data(self) -> RemainingUploadData:
        pass

    @abc.abstractmethod
    def all_done(self) -> bool:
        pass

    @abc.abstractmethod
    def close(self) -> None:
        pass


class FileUploadManager(BaseFileUploadManager):
    """Manages concurrent file uploads."""

    def __init__(
        self,
        rest_client: rest_api_client.OpikApi,
        httpx_client: httpx.Client,
        worker_count: Optional[int],
        file_upload_timeout: Optional[int] = None,
    ) -> None:
        self._httpx_client = httpx_client
        self._rest_client = rest_client

        self._executor = thread_pool.get_thread_pool(worker_count=worker_count)
        self._upload_results: List[UploadResult] = []
        self._file_upload_timeout = file_upload_timeout
        self.closed = False

    def upload(self, message: messages.BaseMessage) -> None:
        if isinstance(message, messages.CreateAttachmentMessage):
            self.upload_attachment(message)
        else:
            raise ValueError(f"Message {message} is not supported for file upload.")

    def upload_attachment(self, attachment: messages.CreateAttachmentMessage) -> None:
        assert isinstance(
            attachment, messages.CreateAttachmentMessage
        ), "Wrong attachment message type"

        options = upload_options.file_upload_options_from_attachment(attachment)
        self._submit_upload(
            uploader=file_uploader.upload_attachment,
            options=options,
        )

    def _submit_upload(
        self, options: upload_options.FileUploadOptions, uploader: Callable
    ) -> None:
        if self.closed:
            LOGGER.warning(
                "The file upload manager has been already closed. No more files can be submitted for upload. (%s)",
                options.file_name,
            )
            return

        monitor = upload_monitor.FileUploadMonitor()
        if options.file_size > 0:
            monitor.total_size = options.file_size

        kwargs = {
            "monitor": monitor,
            "upload_options": options,
            "rest_client": self._rest_client,
            "upload_httpx_client": self._httpx_client,
        }
        future = self._executor.submit(uploader, **kwargs)
        self._upload_results.append(UploadResult(future, monitor))

    def all_done(self) -> bool:
        return all(result.ready() for result in self._upload_results)

    def remaining_data(self) -> RemainingUploadData:
        remaining_uploads = 0
        remaining_bytes_to_upload = 0
        total_size = 0
        for result in self._upload_results:
            monitor = result.monitor
            if monitor.total_size is None or monitor.bytes_sent is None:
                continue

            if result.ready() is True:
                continue

            total_size += monitor.total_size
            remaining_uploads += 1

            remaining_bytes_to_upload += monitor.total_size - monitor.bytes_sent

        return RemainingUploadData(
            uploads=remaining_uploads,
            bytes=remaining_bytes_to_upload,
            total_size=total_size,
        )

    def remaining_uploads(self) -> int:
        """Returns the number of remaining uploads. Can be called at any time."""
        status_list = [result.ready() for result in self._upload_results]
        return status_list.count(False)

    def failed_uploads(self) -> int:
        """Important - this is blocking method waiting for all remaining uploads to complete."""
        failed = 0
        for result in self._upload_results:
            if not result.ready() or not result.successful(self._file_upload_timeout):
                failed += 1

        return failed

    def close(self) -> None:
        self._executor.shutdown(wait=True)
        self.closed = True


class FileUploadManagerMonitor(object):
    def __init__(self, file_upload_manager: FileUploadManager) -> None:
        self.file_upload_manager = file_upload_manager
        self.last_remaining_bytes = 0
        self.last_remaining_uploads_display: Optional[float] = None

    def log_remaining_uploads(self) -> None:
        remaining = self.file_upload_manager.remaining_data()

        current_time = time.monotonic()

        if remaining.bytes == 0:
            LOGGER.info(
                "All assets have been sent, waiting for delivery confirmation",
            )
        elif self.last_remaining_uploads_display is None:
            LOGGER.info(
                "Still uploading %d file(s), remaining %s/%s",
                remaining.uploads,
                format_helpers.format_bytes(remaining.bytes),
                format_helpers.format_bytes(remaining.total_size),
            )
        else:
            uploaded_bytes = self.last_remaining_bytes - remaining.bytes
            time_elapsed = current_time - self.last_remaining_uploads_display
            upload_speed = uploaded_bytes / time_elapsed

            # Avoid 0 division if no bytes were uploaded in the last period
            if uploaded_bytes <= 0:
                # avoid negative upload speed
                if upload_speed < 0:
                    upload_speed = 0

                LOGGER.info(
                    "Still uploading %d asset(s), remaining %s/%s, Throughput %s/s, ETA unknown",
                    remaining.uploads,
                    format_helpers.format_bytes(remaining.bytes),
                    format_helpers.format_bytes(remaining.total_size),
                    format_helpers.format_bytes(upload_speed),
                )

            else:
                # Avoid displaying 0s, also math.ceil returns a float in Python 2.7
                remaining_time = str(int(math.ceil(remaining.bytes / upload_speed)))

                LOGGER.info(
                    "Still uploading %d asset(s), remaining %s/%s, Throughput %s/s, ETA ~%ss",
                    remaining.uploads,
                    format_helpers.format_bytes(remaining.bytes),
                    format_helpers.format_bytes(remaining.total_size),
                    format_helpers.format_bytes(upload_speed),
                    remaining_time,
                )

        self.last_remaining_bytes = remaining.bytes
        self.last_remaining_uploads_display = current_time

    def all_done(self) -> bool:
        return self.file_upload_manager.all_done()
