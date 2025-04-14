import dataclasses
import logging
import math
import time
from concurrent.futures import Future, CancelledError
from typing import Callable, List, Optional

import httpx

from . import upload_options, upload_monitor, thread_pool, file_uploader
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

    def successful(self) -> bool:
        """Allows to check if wrapped Future completed without raising an exception"""
        try:
            return self.future.exception() is None
        except (CancelledError, TimeoutError):
            return False


class FileUploadManager:
    """Manages concurrent file uploads."""

    def __init__(
        self,
        rest_client: rest_api_client.OpikApi,
        httpx_client: httpx.Client,
        worker_cpu_ratio: int,
    ) -> None:
        self._httpx_client = httpx_client
        self._rest_client = rest_client

        self._executor = thread_pool.get_thread_pool(worker_cpu_ratio=worker_cpu_ratio)
        self._upload_results: List[UploadResult] = []
        self.closed = False

    def upload_attachment_file(
        self, attachment: upload_options.FileUploadOptions
    ) -> None:
        self._submit_upload(
            uploader=file_uploader.upload_attachment,
            options=attachment,
        )

    def _submit_upload(
        self, options: upload_options.FileUploadOptions, uploader: Callable
    ) -> None:
        monitor = upload_monitor.FileUploadMonitor()
        if options.file_size > 0:
            monitor.total_size = options.file_size

        kwargs = {
            "monitor": monitor,
            "upload_options": options,
            "rest_client": self._rest_client,
            "httpx_client": self._httpx_client,
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
        """Should be invoked after join() to get a number of failed uploads."""
        assert self.closed, "The file upload manager has not been closed."

        failed = 0
        for result in self._upload_results:
            if not result.ready() or not result.successful():
                failed += 1

        return failed

    def close(self) -> None:
        self._executor.shutdown(wait=False)
        self.closed = True

    def join(self) -> None:
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
            failed_uploads = self.file_upload_manager.failed_uploads()
            if failed_uploads > 0:
                LOGGER.info(
                    "The assets upload has finished, though %d asset(s) was(were) not uploaded successfully. See logs for more details.",
                    failed_uploads,
                )
            else:
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
