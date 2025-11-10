import dataclasses
import logging
import math
import time
from concurrent.futures import Future, CancelledError
from typing import Callable, List, Optional

import httpx

from . import (
    base_upload_manager,
    upload_options,
    file_upload_monitor,
    thread_pool,
    file_uploader,
)
from .. import format_helpers, synchronization
from ..message_processing import messages
from ..rest_api import client as rest_api_client

LOGGER = logging.getLogger(__name__)


@dataclasses.dataclass
class UploadResult:
    future: Future
    monitor: file_upload_monitor.FileUploadMonitor
    upload_options: upload_options.FileUploadOptions

    def ready(self) -> bool:
        """Allows to check if wrapped Future successfully finished"""
        return self.future.done()

    def successful(self, timeout: Optional[float] = None) -> bool:
        """Allows to check if wrapped Future completed without raising an exception"""
        try:
            exception = self.future.exception(timeout)
            if exception is None:
                return True

            raise exception
        except (CancelledError, TimeoutError) as e:
            LOGGER.warning(
                "Timeout while waiting for the result of file '%s' upload. Error: %s",
                self.upload_options.file_name,
                e,
            )
        except Exception as exception:
            LOGGER.error(
                "Failed to upload file with name '%s' from path [%s] with size [%s]. Error: %s",
                self.upload_options.file_name,
                self.upload_options.file_path,
                format_helpers.format_bytes(self.upload_options.file_size),
                exception,
                exc_info=True,
            )

        return False


class FileUploadManagerMonitor:
    def __init__(
        self, file_upload_manager: base_upload_manager.BaseFileUploadManager
    ) -> None:
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
                    "Still uploading %d file(s), remaining %s/%s, Throughput %s/s, ETA unknown",
                    remaining.uploads,
                    format_helpers.format_bytes(remaining.bytes),
                    format_helpers.format_bytes(remaining.total_size),
                    format_helpers.format_bytes(upload_speed),
                )

            else:
                remaining_time = str(int(math.ceil(remaining.bytes / upload_speed)))

                LOGGER.info(
                    "Still uploading %d file(s), remaining %s/%s, Throughput %s/s, ETA ~%ss",
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


class FileUploadManager(base_upload_manager.BaseFileUploadManager):
    """Manages concurrent file uploads."""

    def __init__(
        self,
        rest_client: rest_api_client.OpikApi,
        httpx_client: httpx.Client,
        worker_count: int,
    ) -> None:
        self._httpx_client = httpx_client
        self._rest_client = rest_client

        self._executor = thread_pool.get_thread_pool(worker_count=worker_count)
        self._upload_results: List[UploadResult] = []
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

        monitor = file_upload_monitor.FileUploadMonitor()
        if options.file_size > 0:
            monitor.total_size = options.file_size

        kwargs = {
            "monitor": monitor,
            "upload_options": options,
            "rest_client": self._rest_client,
            "upload_httpx_client": self._httpx_client,
        }
        future = self._executor.submit(uploader, **kwargs)
        self._upload_results.append(
            UploadResult(future, monitor=monitor, upload_options=options)
        )

    def all_done(self) -> bool:
        return all(result.ready() for result in self._upload_results)

    def remaining_data(self) -> base_upload_manager.RemainingUploadData:
        remaining_uploads = 0
        remaining_bytes_to_upload = 0
        total_size = 0
        for result in self._upload_results:
            if result.ready() is True:
                continue

            remaining_uploads += 1

            monitor = result.monitor
            if monitor.total_size is None or monitor.bytes_sent is None:
                continue

            total_size += monitor.total_size
            remaining_bytes_to_upload += monitor.total_size - monitor.bytes_sent

        return base_upload_manager.RemainingUploadData(
            uploads=remaining_uploads,
            bytes=remaining_bytes_to_upload,
            total_size=total_size,
        )

    def remaining_uploads(self) -> int:
        """Returns the number of remaining uploads. Non-blocking - can be called at any time."""
        status_list = [result.ready() for result in self._upload_results]
        return status_list.count(False)

    def failed_uploads(self, timeout: Optional[float]) -> int:
        """Important - this is blocking method waiting for all remaining uploads to complete or while
        timeout is expired."""
        failed = 0
        for result in self._upload_results:
            if not result.ready() or not result.successful(timeout):
                failed += 1

        return failed

    def flush(self, timeout: Optional[float], sleep_time: int = 5) -> bool:
        """Flushes all pending uploads. This is a blocking method that waits for all remaining uploads to complete,
        either until they finish or the specified timeout expires. If no timeout is set, it waits indefinitely.
        Args:
             timeout: Timeout in seconds to wait for all remaining uploads to complete.
                If None is provided, it will wait for all remaining uploads to complete.
            sleep_time: The sleep interval between checks and printing progress.
        Returns:
            The flag to indicate whether all remaining uploads are completed or not within the provided timeout.
        """
        upload_monitor = FileUploadManagerMonitor(self)

        synchronization.wait_for_done(
            check_function=lambda: self.all_done(),
            progress_callback=upload_monitor.log_remaining_uploads,
            timeout=timeout,
            sleep_time=sleep_time,
        )

        # check failed uploads number only if all upload operations completed to avoid blocking
        if self.all_done():
            failed_uploads = self.failed_uploads(timeout)
            if failed_uploads > 0:
                LOGGER.warning(
                    "Failed to upload %d file(s). Check logs for details.",
                    failed_uploads,
                )
            return True

        return False

    def close(self) -> None:
        self._executor.shutdown(wait=True)
        self.closed = True
